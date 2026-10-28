# MCP 脚本化 Agent 演示
#
# 用法（应用需已启动，默认 8081）：
#   powershell -ExecutionPolicy Bypass -File scripts/mcp-agent-demo.ps1
#
# 它扮演一个 Agent，只通过 MCP 工具完成任务：
#   站点 → 设备 → 超期天数 → 提议建单
# 注意：这里没有接大模型，是"固定策略的 Agent"，用来验证工具契约与权限边界。

param(
    [string]$BaseUrl = 'http://localhost:8081',
    [string]$ActorId = 'maint-agent'
)

Add-Type -AssemblyName System.Net.Http
$client = [System.Net.Http.HttpClient]::new()
$rpcId = 0

function Invoke-Mcp {
    param([string]$Method, [hashtable]$Params, [hashtable]$Headers)

    $script:rpcId++
    $payload = @{
        jsonrpc = '2.0'
        id      = $script:rpcId
        method  = $Method
        params  = $Params
    } | ConvertTo-Json -Depth 10 -Compress

    $request = [System.Net.Http.HttpRequestMessage]::new(
        [System.Net.Http.HttpMethod]::Post, "$BaseUrl/mcp")
    foreach ($key in $Headers.Keys) { $request.Headers.Add($key, $Headers[$key]) }
    $request.Content = [System.Net.Http.StringContent]::new(
        $payload, [Text.Encoding]::UTF8, 'application/json')

    $response = $client.SendAsync($request).Result
    $body = $response.Content.ReadAsStringAsync().Result | ConvertFrom-Json
    if ($body.error) { throw "MCP error: $($body.error.message)" }
    return $body.result
}

function Invoke-Tool {
    param([string]$Name, [hashtable]$Arguments, [hashtable]$Headers)

    $result = Invoke-Mcp -Method 'tools/call' -Headers $Headers -Params @{
        name      = $Name
        arguments = $Arguments
    }
    $text = $result.content[0].text
    if ($result.isError) { throw "Tool $Name failed: $text" }
    return $text | ConvertFrom-Json
}

$agentHeaders = @{ 'X-Actor-Id' = $ActorId; 'X-Actor-Type' = 'AGENT' }

Write-Host '== 1. 握手 ==' -ForegroundColor Cyan
$init = Invoke-Mcp -Method 'initialize' -Headers $agentHeaders -Params @{}
Write-Host "server=$($init.serverInfo.name) protocol=$($init.protocolVersion)"

$tools = (Invoke-Mcp -Method 'tools/list' -Headers $agentHeaders -Params @{}).tools
Write-Host "可用工具 $($tools.Count) 个"
$tools | ForEach-Object { '  - ' + $_.name }

Write-Host ''
Write-Host '== 2. 找站点 ==' -ForegroundColor Cyan
$site = (Invoke-Tool -Name 'query_site' -Headers $agentHeaders -Arguments @{
    filters = @{ name = '三号泵站' }
}).items[0]
Write-Host "站点 $($site.id) $($site.name)（$($site.region)）"

Write-Host ''
Write-Host '== 3. 找该站 A 类设备 ==' -ForegroundColor Cyan
$equipment = (Invoke-Tool -Name 'query_equipment' -Headers $agentHeaders -Arguments @{
    filters = @{ siteId = $site.id; criticality = 'A' }
}).items
$equipment | ForEach-Object { Write-Host "  $($_.id) $($_.name) 状态=$($_.status)" }

Write-Host ''
Write-Host '== 4. 问函数：超期几天 ==' -ForegroundColor Cyan
$target = $equipment[0]
$overdue = Invoke-Tool -Name 'fn_overdue_days' -Headers $agentHeaders -Arguments @{
    equipmentId = $target.id
}
Write-Host "$($target.id) nextDue=$($overdue.result.nextDueDate) days=$($overdue.result.days) overdue=$($overdue.result.overdue)"

Write-Host ''
Write-Host '== 5. 提议建单（Agent 只能提议） ==' -ForegroundColor Cyan
$proposal = Invoke-Tool -Name 'action_create_work_order' -Headers $agentHeaders -Arguments @{
    targetId    = $target.id
    equipmentId = $target.id
    type        = '故障'
    priority    = 'P2'
    symptom     = "MCP 演示：$($overdue.result.days) 天未保养，轴承温度偏高"
}
Write-Host "pending=$($proposal.id) status=$($proposal.status)"

Write-Host ''
Write-Host '== 6. Agent 尝试直接执行（应被拒绝） ==' -ForegroundColor Cyan
$execRequest = [System.Net.Http.HttpRequestMessage]::new(
    [System.Net.Http.HttpMethod]::Post, "$BaseUrl/api/actions/pending/$($proposal.id)/execute")
$execRequest.Headers.Add('X-Actor-Id', $ActorId)
$execRequest.Headers.Add('X-Actor-Type', 'AGENT')
$execResponse = $client.SendAsync($execRequest).Result
Write-Host "HTTP $([int]$execResponse.StatusCode)（预期 403）"

Write-Host ''
Write-Host '完成。审批与执行需要主管身份：' -ForegroundColor Green
Write-Host "  POST /api/actions/pending/$($proposal.id)/execute  (X-Actor-Roles: maint_planner)"
Write-Host "  POST /api/actions/pending/$($proposal.id)/approve  (X-Actor-Roles: maint_supervisor)"

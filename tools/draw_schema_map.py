# -*- coding: utf-8 -*-
"""生成元模型关系示意图（docs/assets/schema-map.png）。"""
from PIL import Image, ImageDraw, ImageFont

W, H = 1600, 1520
BG = "#f7f8fa"
INK = "#1f2937"
MUTED = "#6b7280"

C_META = ("#e8f0fe", "#1a73e8")
C_RT = ("#fef3e8", "#e8710a")
C_SRC = ("#f1f5f9", "#64748b")
C_EVAL = ("#e6f4ea", "#1e8e3e")

FONT_DIR = r"C:\Windows\Fonts"
try:
    F_TITLE = ImageFont.truetype(FONT_DIR + r"\msyhbd.ttc", 34)
    F_SUB = ImageFont.truetype(FONT_DIR + r"\msyh.ttc", 17)
    F_HEAD = ImageFont.truetype(FONT_DIR + r"\msyhbd.ttc", 22)
    F_TABLE = ImageFont.truetype(FONT_DIR + r"\msyhbd.ttc", 21)
    F_DESC = ImageFont.truetype(FONT_DIR + r"\msyh.ttc", 15)
    F_STEP = ImageFont.truetype(FONT_DIR + r"\msyhbd.ttc", 14)
except OSError:
    F_TITLE = F_SUB = F_HEAD = F_TABLE = F_DESC = F_STEP = ImageFont.load_default()

img = Image.new("RGB", (W, H), BG)
d = ImageDraw.Draw(img)


def wrap(text, font, max_w):
    lines, cur = [], ""
    for ch in text:
        if d.textlength(cur + ch, font=font) <= max_w:
            cur += ch
        else:
            lines.append(cur)
            cur = ch
    if cur:
        lines.append(cur)
    return lines


def box(x, y, w, h, title, desc, palette, step=""):
    fill, stroke = palette
    d.rounded_rectangle([x, y, x + w, y + h], radius=12, fill=fill, outline=stroke, width=2)
    tx = x + 22
    if step:
        d.text((tx, y + 14), step, font=F_STEP, fill=stroke)
        tx += 34
    d.text((tx, y + 12), title, font=F_TABLE, fill=INK)
    lines = wrap(desc, F_DESC, w - 60)
    ty = y + 46
    for ln in lines[:2]:
        d.text((x + 22, ty), ln, font=F_DESC, fill=MUTED)
        ty += 22


def arrow_down(cx, y1, y2, color="#9aa4b2"):
    d.line([cx, y1, cx, y2 - 8], fill=color, width=2)
    d.polygon([(cx, y2), (cx - 7, y2 - 10), (cx + 7, y2 - 10)], fill=color)


d.text((60, 36), "本体平台元模型：16 张表是怎么连起来的", font=F_TITLE, fill=INK)
d.text((60, 86), "左边是建模时的写入顺序，右边是运行时的读取顺序。箭头 = 上一步产出的东西被下一步引用。",
       font=F_SUB, fill=MUTED)

LX, RW = 60, 690
RX = 850

d.text((LX, 140), "① 建模：本体是怎么被写进去的", font=F_HEAD, fill=C_META[1])
d.text((RX, 140), "② 运行：一次请求读什么、写什么", font=F_HEAD, fill=C_RT[1])

left = [
    ("1", "domain", "建业务域：maint", C_META),
    ("2", "connector", "配数据源：JDBC，指向 maint 库", C_META),
    ("3", "object_type", "注册逻辑对象 equipment，id 从此固定", C_META),
    ("4", "object_type_version", "写 v1 定义；PUBLISHED 后不可再改", C_META),
    ("5", "property_def", "10 个属性，外键挂在“版本”上", C_META),
    ("6", "binding_def", "版本 → 物理表 maint.equipment", C_META),
    ("7", "relation_def", "关系引用逻辑对象，不引用版本", C_META),
    ("8", "function_def / action_def", "函数与动作也引用逻辑对象", C_META),
    ("9", "permission_policy", "权限同样引用逻辑对象", C_META),
    ("10", "ontology_release", "把各对象版本钉成一份发布清单", C_META),
]

right = [
    ("A", "请求", "GET /api/objects/equipment/E-1001", C_RT),
    ("B", "object_type", "按 domain + code 找到逻辑对象 id", C_META),
    ("C", "object_type_version", "取当前 PUBLISHED 的那一版", C_META),
    ("D", "property_def + binding_def", "选字段、拼参数化 SQL", C_META),
    ("E", "源库 maint.equipment", "select … where equipment_id = ?", C_SRC),
    ("F", "permission_policy", "行过滤 + 字段掩码后才返回", C_META),
    ("G", "action_def", "propose：校验前置条件与权限", C_META),
    ("H", "pending_action", "落一条待执行记录，幂等键唯一", C_RT),
    ("I", "audit_log", "execute 后写审计：谁、何时、改了什么", C_RT),
]

BH, GAP = 92, 28
PITCH = BH + GAP
Y0 = 196

for i, (step, name, desc, pal) in enumerate(left):
    y = Y0 + i * PITCH
    box(LX, y, RW, BH, name, desc, pal, step)
    if i < len(left) - 1:
        arrow_down(LX + RW / 2, y + BH, y + PITCH)

for i, (step, name, desc, pal) in enumerate(right):
    y = Y0 + i * PITCH
    box(RX, y, RW, BH, name, desc, pal, step)
    if i < len(right) - 1 and i != 5:
        c = "#f0b27a" if pal is C_RT else "#9aa4b2"
        arrow_down(RX + RW / 2, y + BH, y + PITCH, c)

# 查询 / 动作 分界
div_y = Y0 + 6 * PITCH - GAP // 2
x = RX
while x < RX + RW:
    d.line([x, div_y, min(x + 12, RX + RW), div_y], fill="#d0d7e2", width=2)
    x += 20

# 评测单独一块
EY = Y0 + len(right) * PITCH + 10
box(RX, EY, RW, 76, "eval_case", "每次发布前跑这组金标准 + 对抗用例", C_EVAL, "J")

# 底部提示
by = EY + 96
d.rounded_rectangle([LX, by, LX + 1480, by + 74], radius=12, fill="#ffffff", outline="#d0d7e2", width=2)
d.text((LX + 22, by + 12), "只记这 6 张，就能跑通“查询 + 一个动作 + 审计”：", font=F_DESC, fill=INK)
d.text((LX + 22, by + 40), "domain · object_type · property_def · binding_def · action_def · audit_log",
       font=F_TABLE, fill=INK)

img.save(r"D:\JAVASource\ontology-demo\docs\assets\schema-map.png")
print("saved")

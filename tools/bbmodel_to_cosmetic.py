#!/usr/bin/env python3
"""
Конвертер Blockbench (.bbmodel) -> косметика KitKatVisuals.

  python3 bbmodel_to_cosmetic.py backpack.bbmodel --id backpack --anchor 8,18,10 --out <папка assets/kitkatvisuals>

Кладёт  <out>/cosmetics/<id>.json  и  <out>/textures/cosmetics/<texture>.png.  Потом допиши <id> в CosmeticModel.IDS.

Что умеет: кубы (java_block / free), повороты кубов и групп (порядок осей ZYX, как в Blockbench), UV по граням (с поворотом),
текстуру из base64. Если в модели есть анимация, группы становятся «костями»: поворот и масштаб по ключевым кадрам
(linear / catmullrom), цикл. Без анимации выдаёт обычный статичный набор квадов.

Система координат моделей автора: x=0 по центру, y=0 на уровне ног, спина тела на z=4 (+Z = назад). Мод ждёт центр тела в
x=8, z=8 (спина на z=10), поэтому по умолчанию сдвиг --shift 8,0,6.
"""
import argparse, base64, json, math, os, sys

# вершины граней как в ванильном FaceBakery (наружу; UV: v0=(u1,v1) v1=(u1,v2) v2=(u2,v2) v3=(u2,v1))
FACES = {
    "down":  lambda a, b: [(a[0], a[1], b[2]), (a[0], a[1], a[2]), (b[0], a[1], a[2]), (b[0], a[1], b[2])],
    "up":    lambda a, b: [(a[0], b[1], a[2]), (a[0], b[1], b[2]), (b[0], b[1], b[2]), (b[0], b[1], a[2])],
    "north": lambda a, b: [(b[0], b[1], a[2]), (b[0], a[1], a[2]), (a[0], a[1], a[2]), (a[0], b[1], a[2])],
    "south": lambda a, b: [(a[0], b[1], b[2]), (a[0], a[1], b[2]), (b[0], a[1], b[2]), (b[0], b[1], b[2])],
    "west":  lambda a, b: [(a[0], b[1], a[2]), (a[0], a[1], a[2]), (a[0], a[1], b[2]), (a[0], b[1], b[2])],
    "east":  lambda a, b: [(b[0], b[1], b[2]), (b[0], a[1], b[2]), (b[0], a[1], a[2]), (b[0], b[1], a[2])],
}


def rot_zyx(v, r, o):
    """Поворот точки v на Эйлер r (градусы) вокруг o; Blockbench: R = Rz*Ry*Rx (сначала X)."""
    x, y, z = v[0] - o[0], v[1] - o[1], v[2] - o[2]
    rx, ry, rz = (math.radians(a) for a in r)
    y, z = y * math.cos(rx) - z * math.sin(rx), y * math.sin(rx) + z * math.cos(rx)
    x, z = x * math.cos(ry) + z * math.sin(ry), -x * math.sin(ry) + z * math.cos(ry)
    x, y = x * math.cos(rz) - y * math.sin(rz), x * math.sin(rz) + y * math.cos(rz)
    return (x + o[0], y + o[1], z + o[2])


def element_quads(e, tw, th, inset=0.08):
    """Квады одного куба (повороты самого куба применены): список из 20 чисел на квад."""
    a, b = e["from"], e["to"]
    inf = e.get("inflate", 0) or 0
    a = [a[0] - inf, a[1] - inf, a[2] - inf]
    b = [b[0] + inf, b[1] + inf, b[2] + inf]
    r = e.get("rotation") or [0, 0, 0]
    o = e.get("origin") or [0, 0, 0]
    out = []
    for name, fn in FACES.items():
        f = (e.get("faces") or {}).get(name)
        if not f or f.get("texture") is None:
            continue
        u1, v1, u2, v2 = f["uv"]
        # чуть внутрь клетки, чтобы соседний пиксель палитры не «подмешивался» на границе
        du = inset if u2 >= u1 else -inset
        dv = inset if v2 >= v1 else -inset
        u1, u2, v1, v2 = u1 + du, u2 - du, v1 + dv, v2 - dv
        uv = [(u1, v1), (u1, v2), (u2, v2), (u2, v1)]
        k = (f.get("rotation") or 0) // 90 % 4          # поворот UV грани
        uv = [uv[(i + k) % 4] for i in range(4)]
        q = []
        for (vx, vy, vz), (u, v) in zip(fn(a, b), uv):
            p = rot_zyx((vx, vy, vz), r, o) if any(r) else (vx, vy, vz)
            q += [p[0], p[1], p[2], u / tw, v / th]
        out.append(q)
    return out


def shifted(q, s):
    r = list(q)
    for i in range(0, len(r), 5):
        r[i] += s[0]; r[i + 1] += s[1]; r[i + 2] += s[2]
    return r


def fmt(vals):
    return [round(x, 4) for x in vals]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("model")
    ap.add_argument("--id", required=True)
    ap.add_argument("--texture", help="имя png без расширения (по умолчанию = id)")
    ap.add_argument("--anchor", default="8,18,10", help="центр масштабирования x,y,z в системе мода")
    ap.add_argument("--shift", default="8,0,6", help="сдвиг из системы автора в систему мода")
    ap.add_argument("--out", default=".", help="папка assets/kitkatvisuals")
    ap.add_argument("--def", dest="defs", default="", help="ox,oy,oz,scale по умолчанию")
    args = ap.parse_args()
    shift = [float(x) for x in args.shift.split(",")]
    anchor = [float(x) for x in args.anchor.split(",")]
    tex = args.texture or args.id

    d = json.load(open(args.model, encoding="utf-8"))
    t0 = d["textures"][0]
    tw = float(t0.get("uv_width") or d.get("resolution", {}).get("width") or 16)
    th = float(t0.get("uv_height") or d.get("resolution", {}).get("height") or 16)
    els = {e["uuid"]: e for e in d["elements"] if e.get("export", True) is not False}

    out = {"id": args.id, "texture": tex, "anchor": anchor}
    if args.defs:
        v = [float(x) for x in args.defs.split(",")]
        out["def"] = dict(zip(("ox", "oy", "oz", "scale"), v))

    anims = d.get("animations") or []
    anim = anims[0] if anims else None
    bones = []

    def build(group, parent, base_rot_chain):
        """Кости (анимированная модель): кубы группы -> квады в «сырых» координатах, группа -> кость."""
        idx = len(bones)
        bone = {"n": group["name"], "p": parent, "pv": fmt([group.get("origin", [0, 0, 0])[i] + shift[i] for i in range(3)]),
                "r": [float(x) for x in (group.get("rotation") or [0, 0, 0])], "q": [], "_uuid": group["uuid"]}
        bones.append(bone)
        for c in group.get("children", []):
            if isinstance(c, str):
                if c in els:
                    for q in element_quads(els[c], tw, th):
                        bone["q"] += shifted(q, shift)
            else:
                build(c, idx, None)

    def flatten_static(group, chain, acc):
        """Статичная модель: применяем повороты групп (цепочка от корня) к квадам."""
        rot = group.get("rotation") or [0, 0, 0]
        org = group.get("origin") or [0, 0, 0]
        chain = chain + [(rot, org)]
        for c in group.get("children", []):
            if isinstance(c, str):
                if c in els:
                    for q in element_quads(els[c], tw, th):
                        for r, o in reversed(chain):          # от внутренней группы к внешней
                            if any(r):
                                for i in range(0, 20, 5):
                                    p = rot_zyx(q[i:i + 3], r, o)
                                    q[i], q[i + 1], q[i + 2] = p
                        acc += shifted(q, shift)
            else:
                flatten_static(c, chain, acc)

    top_groups = [g for g in d.get("outliner", []) if isinstance(g, dict)]
    loose = [c for c in d.get("outliner", []) if isinstance(c, str)]

    if anim:
        for g in top_groups:
            build(g, -1, None)
        if loose:                                            # кубы вне групп: неподвижная кость
            bones.append({"n": "_root", "p": -1, "pv": [0, 0, 0], "r": [0, 0, 0], "_uuid": None,
                          "q": sum((shifted(q, shift) for c in loose if c in els for q in element_quads(els[c], tw, th)), [])})
        by_uuid = {b["_uuid"]: b for b in bones if b["_uuid"]}
        n_kf = 0
        for uid, an in anim.get("animators", {}).items():
            b = by_uuid.get(uid)
            if not b:
                continue
            for kf in an.get("keyframes", []):
                ch = kf.get("channel")
                if ch not in ("rotation", "scale"):
                    print("  пропущен канал", ch, "у", b["n"], "(поддерживаются rotation и scale)", file=sys.stderr)
                    continue
                dp = kf["data_points"][0]
                try:
                    vals = [float(dp.get(k, 0 if ch == "rotation" else 1)) for k in "xyz"]
                except (TypeError, ValueError):
                    print("  пропущен кадр с формулой у", b["n"], file=sys.stderr)
                    continue
                key = "ar" if ch == "rotation" else "as"
                b.setdefault(key, []).append([round(kf["time"], 4)] + [round(v, 4) for v in vals])
                b["i" + key[1]] = "c" if kf.get("interpolation") == "catmullrom" else "l"
                n_kf += 1
        for b in bones:
            for key in ("ar", "as"):
                if key in b:
                    b[key].sort(key=lambda k: k[0])
            b["q"] = fmt(b["q"])
            b.pop("_uuid", None)
        out["bones"] = [b for b in bones]
        out["len"] = float(anim.get("length", 1.0))
        out["loop"] = anim.get("loop", "loop") == "loop"
        print("кости: %d, ключевых кадров: %d, длина %.2f с" % (len(bones), n_kf, out["len"]))
        quads = sum(len(b["q"]) // 20 for b in bones)
    else:
        acc = []
        for g in top_groups:
            flatten_static(g, [], acc)
        for c in loose:
            if c in els:
                for q in element_quads(els[c], tw, th):
                    acc += shifted(q, shift)
        out["q"] = fmt(acc)
        quads = len(acc) // 20

    base = args.out
    os.makedirs(os.path.join(base, "cosmetics"), exist_ok=True)
    os.makedirs(os.path.join(base, "textures", "cosmetics"), exist_ok=True)
    with open(os.path.join(base, "cosmetics", args.id + ".json"), "w", encoding="utf-8") as f:
        json.dump(out, f, separators=(",", ":"))
    src = t0.get("source", "")
    if "base64," in src:
        with open(os.path.join(base, "textures", "cosmetics", tex + ".png"), "wb") as f:
            f.write(base64.b64decode(src.split("base64,", 1)[1]))
    else:
        print("ВНИМАНИЕ: текстура не встроена в модель, положи %s.png вручную" % tex, file=sys.stderr)
    print("%s: %d квадов -> %s" % (args.id, quads, os.path.join(base, "cosmetics", args.id + ".json")))


if __name__ == "__main__":
    main()

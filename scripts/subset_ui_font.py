#!/usr/bin/env python3
"""将合法取得的 Noto Sans SC 转为面板用静态子集；不修改系统字体或游戏默认字体。

开发时安装 fonttools==4.60.2；输入为 Google Fonts 官方原件。
字体许可随产物保留，缺字由 Minecraft 默认字体回退。
"""
from pathlib import Path
import argparse
import hashlib
from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

ROOT = Path(__file__).resolve().parents[1]
SOURCE_GIT_BLOB = "fb0637bafbcd804fe32152370a1225990745b4bc"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    args = parser.parse_args()
    raw = args.source.read_bytes()
    digest = hashlib.sha1(f"blob {len(raw)}\0".encode() + raw).hexdigest()
    if digest != SOURCE_GIT_BLOB:
        raise ValueError("字体原件与已核查的 Google Fonts Git blob 不符")
    characters = set(chr(value) for value in range(32, 127))
    for path in (ROOT / "src").rglob("*.java"):
        characters.update(path.read_text(encoding="utf-8-sig"))
    characters.update("←↑↓→−+×≈…：；，。（）【】已探索地表地图透明玻璃重置开关")
    font = TTFont(args.source)
    options = subset.Options()
    options.name_IDs = [0, 1, 2, 3, 4, 5, 6, 7, 13, 14, 16, 17]
    options.name_legacy = True
    options.name_languages = ["*"]
    reducer = subset.Subsetter(options=options)
    reducer.populate(unicodes=[ord(value) for value in characters if ord(value) >= 32])
    reducer.subset(font)
    font = instantiateVariableFont(font, {"wght": 450}, inplace=True)
    # 派生子集使用独立名称，保留原作者、版权与许可记录。
    names = {1: "WorldSeedLab UI", 2: "Regular", 3: "WorldSeedLab-UI-20261023",
             4: "WorldSeedLab UI Regular", 6: "WorldSeedLabUI-Regular",
             16: "WorldSeedLab UI", 17: "Regular"}
    for entry in font["name"].names:
        if entry.nameID in names:
            entry.string = names[entry.nameID].encode(entry.getEncoding())
    target = ROOT / "src/main/resources/assets/worldseedlab/font/ui.ttf"
    target.parent.mkdir(parents=True, exist_ok=True)
    font.save(target)
    result = TTFont(target)
    print(f"UI_FONT_GLYPHS={len(result.getBestCmap())} BYTES={target.stat().st_size}")
    print("SHA256=" + hashlib.sha256(target.read_bytes()).hexdigest())


if __name__ == "__main__":
    main()

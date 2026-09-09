"""构建内嵌字体：LXGW 子集化 woff2 + Inter / JetBrains Mono。

依赖：
  - fonttools + brotli（Python venv）
  - node_modules/@fontsource/inter、@fontsource/jetbrains-mono

输出：src/assets/fonts/*.woff2
"""
import os
import shutil
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
TOOLS_FONTS = os.path.join(ROOT, 'tools', 'fonts')
OUT_DIR = os.path.join(ROOT, 'src', 'assets', 'fonts')
NM = os.path.join(ROOT, 'node_modules')

EXTRA_SYMBOLS = (
    '，。、；：？！“”‘’（）《》〈〉【】「」『』'
    '—…·￥％＃＠～＋－×÷＝≤≥±°′″§¶'
    '→←↑↓✓✔✗⚠'
)
UI_TEXT = (
    '系统一键更新检查项可管理员就绪缺失没有所软件都是最新版本'
    '点击开始扫描运行日志清空成功失败停止选中全部正在共完成'
    '全局包更新跳过未安装无法获取版本信息提示需要以身份'
)


def build_charset() -> str:
    chars = set()
    for code in range(0x20, 0x7F):
        chars.add(chr(code))
    # GB2312 一二级汉字，覆盖日常用字
    for hi in range(0xB0, 0xF8):
        for lo in range(0xA1, 0xFF):
            try:
                chars.add(bytes([hi, lo]).decode('gb2312'))
            except UnicodeDecodeError:
                continue
    chars.update(EXTRA_SYMBOLS)
    chars.update(UI_TEXT)
    return ''.join(sorted(chars))


def subset_lxgw() -> bool:
    src = os.path.join(TOOLS_FONTS, 'LXGWNeoXiHeiPlus.ttf')
    if not os.path.isfile(src):
        print('skip: 未找到', src)
        return False

    charset_path = os.path.join(TOOLS_FONTS, 'subset-chars.txt')
    with open(charset_path, 'w', encoding='utf-8') as f:
        f.write(build_charset())

    out = os.path.join(OUT_DIR, 'LXGWNeoXiHeiPlus.woff2')
    cmd = [
        sys.executable, '-m', 'fontTools.subset', src,
        f'--text-file={charset_path}',
        f'--output-file={out}',
        '--flavor=woff2',
        '--no-hinting',
        '--desubroutinize',
        '--drop-tables+=DSIG',
        '--name-IDs=*',
        '--notdef-outline',
    ]
    subprocess.run(cmd, check=True)
    print('written', out, os.path.getsize(out), 'bytes')
    return True


COPIES = [
    ('@fontsource/inter/files/inter-latin-400-normal.woff2', 'Inter.woff2'),
    ('@fontsource/inter/files/inter-latin-700-normal.woff2', 'Inter-Bold.woff2'),
    ('@fontsource/jetbrains-mono/files/jetbrains-mono-latin-400-normal.woff2',
     'JetBrainsMono.woff2'),
    ('@fontsource/jetbrains-mono/files/jetbrains-mono-latin-700-normal.woff2',
     'JetBrainsMono-Bold.woff2'),
]


def copy_latin_fonts() -> None:
    for rel, name in COPIES:
        src = os.path.join(NM, *rel.split('/'))
        dst = os.path.join(OUT_DIR, name)
        if not os.path.isfile(src):
            print('skip(missing):', rel)
            continue
        shutil.copyfile(src, dst)
        print('written', dst, os.path.getsize(dst), 'bytes')


def main() -> None:
    os.makedirs(OUT_DIR, exist_ok=True)
    subset_lxgw()
    copy_latin_fonts()


if __name__ == '__main__':
    main()

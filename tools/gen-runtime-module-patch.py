#!/usr/bin/env python3
"""
生成 androidx.compose.runtime 的 kotlin_module 补丁

背景：SPW 更新后（1.19.1 观察到）宿主 fat jar 里的 META-INF/runtime.kotlin_module
被清成 24 字节空壳，Kotlin 编译器因此看不到 androidx.compose.runtime 及其子包的
顶层函数（remember / mutableStateOf / LaunchedEffect …），插件源码全部报
Unresolved reference。

本脚本扫描宿主 jar，按 KotlinModuleProto 格式重建该模块（只含包 → facade 类映射），
输出一个只含 META-INF/runtime.kotlin_module 的小 jar，作为 compileOnly 前置依赖。

用法：
    python tools/gen-runtime-module-patch.py [宿主jar] [输出jar]
默认：
    宿主 jar = gradle.properties 里的 spwHostJar（未解析，请显式传入）
    输出     = libs/runtime-module-patch.jar

宿主以后如果把模块修好了，这个补丁可以删掉（build.gradle.kts 里同步移除）。
"""
import sys
import os
import zipfile

DEFAULT_HOST = r"D:\Games\Steam\Steam\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll"
DEFAULT_OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                           "libs", "runtime-module-patch.jar")


def varint(n: int) -> bytes:
    out = b""
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out += bytes([b | 0x80])
        else:
            out += bytes([b])
            return out


def field_str(num: int, s: str) -> bytes:
    b = s.encode("utf-8")
    return bytes([(num << 3) | 2]) + varint(len(b)) + b


def main() -> int:
    host = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_HOST
    out = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_OUT
    z = zipfile.ZipFile(host)

    # 借一个结构正常的模块的 20 字节文件头（saveable 通常不受影响）
    ref = z.read("META-INF/runtime-saveable.kotlin_module")

    prefix = "androidx/compose/runtime/"
    pkgs = {}
    for n in z.namelist():
        if not n.startswith(prefix) or not n.endswith(".class") or "$" in n:
            continue
        rel = n[len(prefix):]
        parts = rel.split("/")
        fname = parts[-1][:-6]
        if not fname.endswith("Kt"):
            continue
        pkg = "androidx.compose.runtime" + (("." + ".".join(parts[:-1])) if len(parts) > 1 else "")
        pkgs.setdefault(pkg, []).append(fname)

    if not pkgs:
        print("没有找到 androidx.compose.runtime 的 facade 类，宿主结构可能又变了")
        return 1

    module = b""
    for pkg in sorted(pkgs):
        body = field_str(1, pkg)
        for f in sorted(pkgs[pkg]):
            body += field_str(2, f)
        module += b"\x0a" + varint(len(body)) + body

    data = ref[:20] + module
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as w:
        w.writestr("META-INF/runtime.kotlin_module", data)
    print("packages=%d size=%d -> %s" % (len(pkgs), len(data), out))
    return 0


if __name__ == "__main__":
    sys.exit(main())

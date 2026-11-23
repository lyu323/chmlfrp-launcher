#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
OUT_DIR="${OUT_DIR:-$PWD/out}"
mkdir -p "$OUT_DIR"

# 本地工具链路径，可用环境变量覆盖（仓库不附带任何 SDK / 密钥 / 内核二进制）
export JH="${JH:-/path/to/jdk17}"
export BT="${BT:-/path/to/android-build-tools}"
export AJ="${AJ:-/path/to/android-35/android.jar}"
export LD_LIBRARY_PATH="$BT/lib64:${LD_LIBRARY_PATH:-}"
export JAVA_HOME="$JH"
export PATH="$JH/bin:$PATH"

echo "== 清理 =="
rm -rf classes dexout gen res.zip base-*.apk work-*.apk aligned-*.apk \
       libstage-arm64 libstage-all
mkdir -p classes dexout gen

echo "== 生成图标 =="
python3 make_icon.py

echo "== aapt2 compile res =="
$BT/aapt2 compile --dir res -o res.zip

echo "== javac =="
$JH/bin/javac -encoding UTF-8 -source 8 -target 8 \
    -bootclasspath "$AJ" -cp "$AJ" -d classes $(find src -name '*.java')
echo "classes: $(find classes -name '*.class' | wc -l)"

echo "== d8 =="
$JH/bin/java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
    --lib "$AJ" --min-api 23 --output dexout $(find classes -name '*.class')
ls -lh dexout/classes.dex

echo "== 准备 native libraries =="
# frpc 内核二进制为第三方产物，不随仓库分发：自备后指向 jniLibs 目录
SRC="${FRPC_LIBS_DIR:-../frpc-libs}"
mkdir -p libstage-arm64/lib/arm64-v8a \
         libstage-all/lib/arm64-v8a \
         libstage-all/lib/armeabi-v7a \
         libstage-all/lib/x86_64
cp "$SRC/arm64-v8a/libfrpc.so"    libstage-arm64/lib/arm64-v8a/libfrpc.so
cp "$SRC/arm64-v8a/libfrpc.so"    libstage-all/lib/arm64-v8a/libfrpc.so
cp "$SRC/armeabi-v7a/libfrpc.so"  libstage-all/lib/armeabi-v7a/libfrpc.so
cp "$SRC/x86_64/libfrpc.so"       libstage-all/lib/x86_64/libfrpc.so

build_variant() {
    local variant="$1" stage="$2" outname="$3"
    echo "== aapt2 link: $variant =="
    $BT/aapt2 link -o "base-$variant.apk" -I "$AJ" \
        --manifest AndroidManifest.xml \
        --min-sdk-version 23 --target-sdk-version 35 \
        --version-code 14 --version-name 3.0.1 \
        --java gen res.zip

    WORK_ABS="$PWD/work-$variant.apk"
    cp "base-$variant.apk" "$WORK_ABS"
    zip -q -j "$WORK_ABS" dexout/classes.dex

    echo "== 加入 native libraries (stored, 不压缩): $variant =="
    (cd "$stage" && zip -q -0 -r "$WORK_ABS" lib)

    $BT/zipalign -f 4 "work-$variant.apk" "aligned-$variant.apk"

    # 签名密钥绝不随源码分发：自备 .jks 并用环境变量传入
    KS_FILE="${KS_FILE:-$PWD/chmlfrp.jks}"
    if [ -n "${KS_PASS:-}" ] && [ -f "$KS_FILE" ]; then
        $BT/apksigner sign \
            --ks "$KS_FILE" --ks-pass "pass:$KS_PASS" \
            --key-pass "pass:${KEY_PASS:-$KS_PASS}" \
            --v1-signing-enabled true --v2-signing-enabled true \
            --out "$OUT_DIR/$outname" "aligned-$variant.apk"
        echo "== verify: $outname =="
        $BT/apksigner verify --verbose "$OUT_DIR/$outname"
    else
        echo "== 未配置 KS_PASS / keystore，输出未签名 APK（仅供本地调试）: $outname =="
        cp "aligned-$variant.apk" "$OUT_DIR/$outname"
    fi
    ls -lh "$OUT_DIR/$outname"
}

build_variant arm64 libstage-arm64 "ChmlFrpLauncher-v3.0.1-arm64.apk"
build_variant all   libstage-all  "ChmlFrpLauncher-v3.0.1-allarch.apk"

echo "== 完成 =="

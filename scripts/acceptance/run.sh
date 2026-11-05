#!/usr/bin/env bash
set -euo pipefail

model_dir="${RUNNER_TEMP:?}/lifeatlas-acceptance"
mkdir -p acceptance
model_pid=
proxy_pid=
collect() {
    result=$?
    adb pull /sdcard/Pictures/LifeAtlasAcceptance acceptance/screenshots >/dev/null 2>&1 || true
    adb pull /sdcard/Download/LifeAtlasAcceptance acceptance/device >/dev/null 2>&1 || true
    if [ -n "$proxy_pid" ]; then kill "$proxy_pid" 2>/dev/null || true; fi
    if [ -n "$model_pid" ]; then kill "$model_pid" 2>/dev/null || true; fi
    exit "$result"
}
trap collect EXIT

# TLS remains enabled end to end. Only the instrumented test trusts this CA.
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj '/CN=127.0.0.1' \
    -addext 'subjectAltName=IP:127.0.0.1' -keyout "$model_dir/test-key.pem" \
    -out "$model_dir/test-cert.pem" 2>/dev/null
certificate=$(openssl x509 -in "$model_dir/test-cert.pem" -outform DER | base64 -w0)

LD_LIBRARY_PATH="$model_dir/bin" "$model_dir/bin/llama-server" \
    --model "$model_dir/qwen.gguf" --alias lifeatlas-acceptance \
    --host 127.0.0.1 --port 9081 --threads 4 --ctx-size 16384 --parallel 1 \
    --n-predict 2048 >acceptance/model-server.log 2>&1 &
model_pid=$!
for attempt in $(seq 1 90); do
    if curl -fsS http://127.0.0.1:9081/health >/dev/null 2>&1; then break; fi
    kill -0 "$model_pid"
    sleep 2
done
curl -fsS http://127.0.0.1:9081/health >/dev/null
python3 scripts/acceptance/provider_proxy.py "$model_dir/test-cert.pem" "$model_dir/test-key.pem" \
    >acceptance/tls-proxy.log 2>&1 &
proxy_pid=$!
adb reverse tcp:9443 tcp:9443
adb shell settings put secure show_ime_with_hard_keyboard 1
./gradlew :app:connectedStandaloneDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.lifeatlas.app.LanguageAcceptanceTest \
    "-Pandroid.testInstrumentationRunnerArguments.atlasAcceptanceCert=$certificate" --stacktrace

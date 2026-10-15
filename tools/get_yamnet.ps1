# 模型安装脚本（PowerShell）
$url = 'https://tfhub.dev/google/lite-model/yamnet/classification/tflite/1?lite-format=tflite'
$out = 'app/src/main/assets/yamnet.tflite'
New-Item -ItemType Directory -Force (Split-Path $out) | Out-Null
Invoke-WebRequest -Uri $url -OutFile $out
$sha = (Get-FileHash $out -Algorithm SHA256).Hash
Write-Host "YAMNet downloaded; SHA-256=$sha"
Write-Host '请用官方 yamnet_class_map.csv 覆盖 yamnet_labels.txt，确保 521 行顺序一致。'

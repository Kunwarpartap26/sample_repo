# Vosk Offline Speech-to-Text Model
#
# Download a small English model from: https://alphacephei.com/vosk/models
#
# Recommended models (smallest first):
#   - vosk-model-small-en-us-0.15   (~40MB)  — good for most use cases
#   - vosk-model-small-en-us-0.22   (~40MB)  — newer, slightly better accuracy
#   - vosk-model-en-us-0.22         (~1.8GB) — full model, highest accuracy
#   - vosk-model-small-en-us-0.15   (~40MB)  — "tiny" for ultra-low-RAM devices
#
# Extract the model directory here so the structure is:
#   assets/vosk-model/
#   ├── conf/
#   ├── graph/
#   ├── am/
#   └── ivector/
#
# DO NOT commit model files to version control (large binary files)

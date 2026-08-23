import re

with open("app/src/test/kotlin/com/minibrain/ai/llm/ModelDownloaderTest.kt", "r") as f:
    content = f.read()

print("errorResult != null", "errorResult != null" in content)

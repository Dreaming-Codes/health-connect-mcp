#!/bin/bash
# helpers for emulator UI tests
A=/workspace/android-sdk/platform-tools/adb
shot() { timeout 60 $A exec-out screencap -p > /workspace/health-connect-mcp/screens/$1.png && echo "shot $1"; }
tapText() { # tap center of first node whose text or content-desc matches $1
  timeout 90 $A shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; timeout 20 $A pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
  local b=$(python3 - "$1" <<'PY'
import sys,re
x=open('/tmp/ui.xml').read(); t=sys.argv[1]
for m in re.finditer(r'<node [^>]*>', x):
  n=m.group(0)
  if re.search(r'(text|content-desc)="%s"'%re.escape(t), n):
    a=list(map(int,re.findall(r'\d+', re.search(r'bounds="([^"]+)"',n).group(1)))); print((a[0]+a[2])//2,(a[1]+a[3])//2); break
PY
)
  [ -z "$b" ] && { echo "notfound $1"; return 1; }; timeout 20 $A shell input tap $b; echo "tap $1 @ $b"
}

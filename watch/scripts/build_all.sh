#!/usr/bin/env bash
# Compile-only check of every product in manifest.xml. Writes bin/all/build_all.log and a one-line summary.
cd "$(dirname "$0")/.."
SDK="$(tr -d '\r\n' < "$APPDATA/Garmin/ConnectIQ/current-sdk.cfg")"
KEY="${CIQ_KEY:?set CIQ_KEY to the path of your Connect IQ developer key (.der)}"
mkdir -p bin/all; LOG=bin/all/build_all.log; : > $LOG
ok=0; bad=0
for DEV in $(grep -o 'product id="[^"]*"' manifest.xml | cut -d'"' -f2); do
  OUT=$("$SDK/bin/monkeyc.bat" -f monkey.jungle -d "$DEV" -o "bin/all/$DEV.prg" -y "$KEY" -r 2>&1 | grep -v "launcher icon")
  if [ -f "bin/all/$DEV.prg" ]; then ok=$((ok+1)); echo "OK $DEV $(stat -c %s bin/all/$DEV.prg)" >> $LOG; else bad=$((bad+1)); echo "FAIL $DEV: $OUT" >> $LOG; fi
done
echo "SUMMARY ok=$ok fail=$bad" | tee -a $LOG

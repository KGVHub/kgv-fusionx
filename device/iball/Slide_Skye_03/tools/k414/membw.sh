#!/bin/bash
# Rough memory bandwidth: 512 MB through dd with 1 MB blocks (memset+memcpy bound).
adb -s 1900567000077 shell 'for i in 1 2; do dd if=/dev/zero of=/dev/null bs=1048576 count=512 2>&1 | tail -1; done'

"""Frozen-app launcher; keeps package-relative imports working."""

import sys

from esp_flasher.__main__ import main

if __name__ == "__main__":
    sys.exit(main())

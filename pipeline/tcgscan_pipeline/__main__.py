"""``python -m tcgscan_pipeline`` runs the command line of :mod:`tcgscan_pipeline.cli`."""

import sys

from .cli import main

sys.exit(main())

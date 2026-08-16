"""Dive log readers, and format detection across them."""

from diveslate.parsers.base import ParseError, Parser
from diveslate.parsers.detect import parse_file, parse_text, sniff
from diveslate.parsers.subsurface import SubsurfaceParser
from diveslate.parsers.uddf import UddfParser

__all__ = [
    "ParseError",
    "Parser",
    "SubsurfaceParser",
    "UddfParser",
    "parse_file",
    "parse_text",
    "sniff",
]

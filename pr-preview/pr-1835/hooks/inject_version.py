"""MkDocs hook to inject version information into pages."""
import re

def on_page_markdown(markdown, page, config, files):
    """Replace {{ version }} placeholders with the actual version."""
    version = config.get("extra", {}).get("version", "0.1.240")
    return markdown.replace("{{ version }}", version)

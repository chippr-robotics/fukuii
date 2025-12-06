"""
MkDocs hook to inject Fukuii version from version.sbt into generated pages.
This ensures the configuration wizard always displays the correct version.
"""

import re
import os


def extract_version_from_sbt():
    """Extract version from version.sbt file."""
    sbt_file = os.path.join(os.path.dirname(__file__), '../../version.sbt')
    
    try:
        with open(sbt_file, 'r') as f:
            content = f.read()
            # Find the version assignment line
            match = re.search(r'version.*:=\s*"([0-9]+\.[0-9]+\.[0-9]+)"', content)
            if match:
                return match.group(1)
    except FileNotFoundError:
        print(f"Warning: version.sbt not found at {sbt_file}")
    
    return "0.1.121"  # Fallback version


def on_page_context(context, page, config, nav):
    """
    Called for each page. Inject version into page context.
    """
    version = extract_version_from_sbt()
    context['fukuii_version'] = version
    return context


def on_post_page(output, page, config):
    """
    Called after page is rendered. Inject version into HTML.
    """
    version = extract_version_from_sbt()
    
    # Inject version as data attribute on html element
    output = output.replace(
        '<html',
        f'<html data-fukuii-version="{version}"',
        1
    )
    
    # Also inject as JavaScript variable for immediate use
    output = output.replace(
        '</head>',
        f'<script>window.__FUKUII_VERSION__ = "{version}";</script>\n</head>',
        1
    )
    
    return output

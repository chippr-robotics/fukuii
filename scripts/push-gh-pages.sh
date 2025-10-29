#!/bin/bash

# Script to push the gh-pages branch to GitHub
# This script should be run by a repository maintainer with push access

set -e

echo "📚 Pushing gh-pages branch to GitHub..."
echo ""

# Check if we're in the right directory
if [ ! -d ".git" ]; then
    echo "❌ Error: Not in a git repository"
    exit 1
fi

# Check if gh-pages branch exists
if ! git show-ref --verify --quiet refs/heads/gh-pages; then
    echo "❌ Error: gh-pages branch does not exist locally"
    echo "   The branch should have been created already."
    exit 1
fi

# Show branch info
echo "Current branch: $(git branch --show-current)"
echo ""
echo "gh-pages branch info:"
git log gh-pages --oneline -1
echo ""

# Confirm push
read -p "Push gh-pages branch to origin? (y/N) " -n 1 -r
echo
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo "Cancelled."
    exit 0
fi

# Push gh-pages branch
echo ""
echo "Pushing gh-pages branch..."
git push -u origin gh-pages

echo ""
echo "✅ gh-pages branch pushed successfully!"
echo ""
echo "Next steps:"
echo "1. Go to https://github.com/chippr-robotics/fukuii/settings/pages"
echo "2. Under 'Source', select 'gh-pages' branch and '/' (root) folder"
echo "3. Click 'Save'"
echo ""
echo "The documentation site will be available at:"
echo "https://chippr-robotics.github.io/fukuii/"
echo ""

#!/bin/bash

# This script builds the documentation and pushes it to github

AUTHOR="Documentation Builder"
GH_REPO_REF="github.com/$TRAVIS_REPO_SLUG"

set -e

cd docs

# Activate virtualenv
virtualenv -p python3 venv
. venv/bin/activate

# Install dependencies
pip install -r requirements.txt

# Build docs
make html

cd _build

# Configure git
git config --global push.default simple
git config user.name "Travis CI"
git config user.email "travis@travis-ci.org"

# Clone docs branch
git clone -b gh-pages https://git@$GH_REPO_REF docs-upload
cd docs-upload

# Remove existing docs
rm -rf *

# Copy docs from build to repo
cp -r ../html/* ./
echo "" > .nojekyll

if [[ -f "index.html" ]]; then
    # Commit
    git add --all
    git commit -m "Deploy code docs to GitHub Pages Travis build"

    git push --force "https://${GH_REPO_TOKEN}@${GH_REPO_REF}" > /dev/null 2>&1
else
    echo 'ERROR: index.html not found'
    exit 1
fi

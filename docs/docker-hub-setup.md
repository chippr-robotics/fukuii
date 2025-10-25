# Docker Hub Setup Guide

This guide explains how to configure Docker Hub integration for automated image builds and distribution.

## Overview

The Fukuii project automatically builds and publishes Docker images to two registries:
1. **GitHub Container Registry (GHCR)** - Automatic, no configuration needed
2. **Docker Hub** - Requires manual setup of credentials

## Prerequisites

- Admin access to the GitHub repository
- Docker Hub account with access to `chipprbots` organization
- Docker Hub access token with push permissions

## Step 1: Create Docker Hub Access Token

1. Log in to [Docker Hub](https://hub.docker.com/)
2. Go to Account Settings → Security
3. Click "New Access Token"
4. Configure the token:
   - **Description**: `GitHub Actions - chordodes_fukuii`
   - **Access permissions**: `Read & Write`
5. Click "Generate"
6. **Important**: Copy the token immediately - it won't be shown again

## Step 2: Configure GitHub Secrets

1. Go to your GitHub repository
2. Navigate to **Settings** → **Secrets and variables** → **Actions**
3. Click **New repository secret**
4. Add the following two secrets:

### Secret 1: DOCKERHUB_USERNAME

- **Name**: `DOCKERHUB_USERNAME`
- **Value**: Your Docker Hub username (e.g., `chipprbots` or your personal username)

### Secret 2: DOCKERHUB_TOKEN

- **Name**: `DOCKERHUB_TOKEN`
- **Value**: The access token you generated in Step 1

## Step 3: Verify Setup

After adding the secrets, the next push or workflow run will attempt to push to Docker Hub.

### Check Workflow Run

1. Go to **Actions** tab in GitHub
2. Select the latest "Docker Build" workflow run
3. Check the logs for "Log in to Docker Hub" step
4. Verify images are pushed to both registries

### Verify Images on Docker Hub

Visit https://hub.docker.com/r/chipprbots/ to see your published images:
- `chipprbots/fukuii`
- `chipprbots/fukuii-base`
- `chipprbots/fukuii-dev`

## Troubleshooting

### Images only pushed to GHCR

**Problem**: Images are only appearing in GitHub Container Registry, not Docker Hub.

**Solution**: Verify secrets are configured correctly:
```bash
# Secrets should be visible in Settings → Secrets and variables → Actions
DOCKERHUB_USERNAME
DOCKERHUB_TOKEN
```

### Authentication Failed

**Problem**: Workflow shows "authentication failed" for Docker Hub.

**Solutions**:
1. Verify the access token hasn't expired
2. Ensure the token has "Read & Write" permissions
3. Check the username is correct (organization name, not personal name)
4. Regenerate the access token if necessary

### Images Have Wrong Tags

**Problem**: Images are tagged incorrectly on Docker Hub.

**Solution**: Check the workflow configuration in `.github/workflows/docker.yml`. The `DOCKERHUB_ORG` environment variable should be set to `chipprbots`.

## Security Best Practices

1. **Never commit tokens to the repository**
   - Always use GitHub Secrets
   - Never hardcode credentials in workflows

2. **Use scoped access tokens**
   - Create separate tokens for different purposes
   - Use "Read & Write" instead of full admin access

3. **Rotate tokens regularly**
   - Update tokens at least annually
   - Rotate immediately if compromised

4. **Limit token access**
   - Create organization-specific tokens when possible
   - Review token permissions regularly

## Optional: Manual Push

If you need to manually push an image (not recommended for production):

```bash
# Login to Docker Hub
docker login docker.io --username <your-username>

# Tag image
docker tag fukuii:latest chipprbots/fukuii:latest

# Push image
docker push chipprbots/fukuii:latest
```

## Support

For issues with:
- **Docker Hub credentials**: Contact Docker Hub support or repository admin
- **GitHub Actions workflow**: Open an issue in the repository
- **Image quality/bugs**: Open an issue with relevant details

## References

- [Docker Hub Access Tokens Documentation](https://docs.docker.com/docker-hub/access-tokens/)
- [GitHub Actions Secrets Documentation](https://docs.github.com/en/actions/security-guides/encrypted-secrets)
- [Project Docker Policy](docker-policy.md)

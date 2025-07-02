# Release Process

This document describes how to release Redlock4j to Maven Central.

## Prerequisites

Before you can publish to Maven Central, you need to set up the following:

### 1. Sonatype OSSRH Account

1. Create an account at [Sonatype OSSRH](https://issues.sonatype.org/)
2. Create a ticket to claim the `org.codarama` namespace
3. Wait for approval (usually takes 1-2 business days)

### 2. GPG Key for Signing

Generate a GPG key for signing artifacts:

```bash
# Generate a new GPG key
gpg --gen-key

# List your keys to get the key ID
gpg --list-secret-keys --keyid-format LONG

# Export the private key (base64 encoded)
gpg --export-secret-keys YOUR_KEY_ID | base64

# Export the public key to upload to key servers
gpg --export YOUR_KEY_ID | base64
```

Upload your public key to key servers:
```bash
gpg --keyserver keyserver.ubuntu.com --send-keys YOUR_KEY_ID
gpg --keyserver keys.openpgp.org --send-keys YOUR_KEY_ID
```

### 3. GitHub Secrets

Set up the following secrets in your GitHub repository:

| Secret Name | Description | Example |
|-------------|-------------|---------|
| `OSSRH_USERNAME` | Your Sonatype OSSRH username | `your-username` |
| `OSSRH_TOKEN` | Your Sonatype OSSRH token/password | `your-token` |
| `GPG_PRIVATE_KEY` | Base64 encoded GPG private key | `LS0tLS1CRUdJTi...` |
| `GPG_PASSPHRASE` | Passphrase for your GPG key | `your-passphrase` |

To set up secrets:
1. Go to your GitHub repository
2. Navigate to Settings → Secrets and variables → Actions
3. Click "New repository secret"
4. Add each secret with the appropriate value

### 4. Environment Protection

1. Go to Settings → Environments
2. Create an environment named `maven-central`
3. Add protection rules (optional but recommended):
   - Required reviewers
   - Wait timer
   - Deployment branches (only `main`)

## Release Process

### Automatic Release (Recommended)

1. **Create a GitHub Release:**
   - Go to the GitHub repository
   - Click "Releases" → "Create a new release"
   - Choose a tag (e.g., `v1.0.0`, `1.0.0`)
   - Fill in the release title and description
   - Click "Publish release"

2. **Automatic Publication:**
   - The GitHub Action will automatically trigger
   - It will run tests, build artifacts, and publish to Maven Central
   - Check the Actions tab for progress

### Manual Release

You can also trigger a release manually:

1. Go to Actions → "Release to Maven Central"
2. Click "Run workflow"
3. Enter the version number (e.g., `1.0.0`)
4. Click "Run workflow"

## Version Management

### Version Format

Use semantic versioning: `MAJOR.MINOR.PATCH`

- **MAJOR**: Incompatible API changes
- **MINOR**: New functionality (backward compatible)
- **PATCH**: Bug fixes (backward compatible)

Examples:
- `1.0.0` - Initial release
- `1.1.0` - New features added
- `1.1.1` - Bug fixes
- `2.0.0` - Breaking changes

### Pre-release Versions

For pre-release versions, append a qualifier:
- `1.0.0-alpha.1`
- `1.0.0-beta.1`
- `1.0.0-rc.1`

## What Happens During Release

1. **Version Update**: The workflow updates the version in `pom.xml`
2. **Testing**: Runs unit and integration tests
3. **Build**: Compiles and packages the JAR files
4. **Documentation**: Generates Javadoc
5. **Signing**: Signs all artifacts with GPG
6. **Upload**: Uploads to Sonatype OSSRH staging repository
7. **Release**: Automatically promotes to Maven Central

## Artifacts Published

The following artifacts are published to Maven Central:

- `redlock4j-{version}.jar` - Main library
- `redlock4j-{version}-sources.jar` - Source code
- `redlock4j-{version}-javadoc.jar` - API documentation
- `redlock4j-{version}.pom` - Maven metadata

All artifacts are signed with GPG.

## Verification

After release, verify the publication:

1. **Maven Central Search**: https://central.sonatype.com/search?q=redlock4j
2. **Maven Repository**: https://repo1.maven.org/maven2/org/codarama/redlock4j/
3. **Test Installation**:
   ```xml
   <dependency>
       <groupId>org.codarama</groupId>
       <artifactId>redlock4j</artifactId>
       <version>YOUR_VERSION</version>
   </dependency>
   ```

## Troubleshooting

### Common Issues

1. **GPG Signing Fails**
   - Check that `GPG_PRIVATE_KEY` is correctly base64 encoded
   - Verify `GPG_PASSPHRASE` is correct
   - Ensure the GPG key hasn't expired

2. **OSSRH Authentication Fails**
   - Verify `OSSRH_USERNAME` and `OSSRH_TOKEN` are correct
   - Check if your OSSRH account has permission for the namespace

3. **Staging Repository Issues**
   - Check the Sonatype OSSRH staging repository
   - Look for validation errors in the staging repository

4. **Tests Fail**
   - The workflow runs tests before publishing
   - Fix any test failures before attempting release

### Getting Help

- Check the GitHub Actions logs for detailed error messages
- Review the [Sonatype OSSRH Guide](https://central.sonatype.org/publish/publish-guide/)
- Create an issue in the repository if you encounter problems

## Post-Release

After a successful release:

1. **Update Documentation**: Update README.md with the new version
2. **Announce**: Announce the release in relevant channels
3. **Monitor**: Watch for any issues reported by users
4. **Plan Next Release**: Plan features for the next version

## Security Considerations

- Keep GPG keys secure and rotate them periodically
- Use strong passphrases for GPG keys
- Limit access to GitHub secrets
- Monitor release workflows for any suspicious activity
- Consider using environment protection rules for additional security

# Redlock4j Documentation

This directory contains the source files for the Redlock4j documentation, built with [MkDocs](https://www.mkdocs.org/) and the [Material theme](https://squidfunk.github.io/mkdocs-material/).

## Local Development

### Prerequisites

- Python 3.8 or higher
- pip

### Setup

1. Install dependencies:

```bash
pip install -r requirements.txt
```

Or using a virtual environment (recommended):

```bash
python3 -m venv venv
source venv/bin/activate  # On Windows: venv\Scripts\activate
pip install -r requirements.txt
```

### Build Documentation

Build the static site:

```bash
mkdocs build
```

The built site will be in the `site/` directory.

### Serve Locally

Run a local development server with live reload:

```bash
mkdocs serve
```

Then open http://127.0.0.1:8000 in your browser.

### Strict Mode

Build with strict mode to catch warnings:

```bash
mkdocs build --strict
```

## Deployment

Documentation is automatically deployed to GitHub Pages when changes are pushed to the `main` branch.

The deployment is handled by the `.github/workflows/docs.yml` workflow.

### Manual Deployment

To manually deploy:

```bash
mkdocs gh-deploy
```

This will build the site and push it to the `gh-pages` branch.

## Documentation Structure

```
docs/
├── index.md                    # Home page
├── getting-started/
│   ├── installation.md         # Installation guide
│   └── quick-start.md          # Quick start guide
├── guide/
│   ├── basic-usage.md          # Basic usage patterns
│   ├── advanced-locking.md     # Advanced locking features
│   ├── redis-clients.md        # Redis client configuration
│   └── best-practices.md       # Best practices
├── api/
│   ├── core.md                 # Core API reference
│   └── configuration.md        # Configuration API reference
├── development/
│   ├── contributing.md         # Contributing guide
│   └── release.md              # Release process
└── comparison.md               # Comparison with Redisson
```

## Configuration

The site configuration is in `mkdocs.yml` at the repository root.

Key configuration sections:

- **site_name**: Site title
- **theme**: Material theme configuration
- **nav**: Navigation structure
- **markdown_extensions**: Markdown features
- **plugins**: MkDocs plugins

## Writing Documentation

### Markdown Features

The documentation supports:

- **Admonitions**: `!!! note`, `!!! warning`, `!!! tip`
- **Code blocks**: With syntax highlighting
- **Tables**: Standard Markdown tables
- **Tabs**: For multiple code examples

### Code Blocks

```markdown
\`\`\`java
Lock lock = redlock.lock("resource", 10000);
\`\`\`
```

### Admonitions

```markdown
!!! warning "Important"
    Always release locks in a finally block.
```

### Tabs

```markdown
=== "Jedis"
    \`\`\`java
    JedisPool pool = new JedisPool("localhost", 6379);
    \`\`\`

=== "Lettuce"
    \`\`\`java
    RedisClient client = RedisClient.create("redis://localhost:6379");
    \`\`\`
```

## Contributing

To contribute to the documentation:

1. Edit the relevant `.md` files in the `docs/` directory
2. Test locally with `mkdocs serve`
3. Submit a pull request

## Links

- [MkDocs Documentation](https://www.mkdocs.org/)
- [Material for MkDocs](https://squidfunk.github.io/mkdocs-material/)
- [Markdown Guide](https://www.markdownguide.org/)


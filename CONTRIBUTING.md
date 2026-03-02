# Contributing to Redlock4j

Thank you for your interest in contributing to Redlock4j! This document provides guidelines and information for contributors.

## 🚀 Quick Start

1. **Fork the repository** on GitHub
2. **Clone your fork** locally
3. **Create a feature branch** from `main`
4. **Make your changes** with tests
5. **Run tests locally** to ensure everything works
6. **Submit a pull request**

## 🧪 Testing

### Test Organization
Tests are organized using JUnit 5 `@Tag` annotations and Maven lifecycle phases:

- **Unit tests** (`@Tag("unit")`) - Run during `test` phase via maven-surefire-plugin
- **Integration tests** (`@Tag("integration")`) - Run during `integration-test` phase via maven-failsafe-plugin
- **Performance tests** (`@Tag("performance")`) - Run on-demand or during nightly builds

### Local Testing
Before submitting a PR, run the tests locally:

```bash
# Run unit tests only (fast, no Docker required)
mvn test

# Run unit + integration tests (requires Docker for Testcontainers)
mvn verify

# Run only integration tests (skip unit tests)
mvn failsafe:integration-test failsafe:verify

# Run performance tests
mvn test -Dgroups=performance

# Run with coverage
mvn verify jacoco:report

# Security scan
mvn org.owasp:dependency-check-maven:check
```

## 📝 Code Guidelines

### Code Style
We use automated code formatting to ensure consistency across the codebase:

- **Formatter**: Eclipse formatter with custom configuration (`formatting.xml`)
- **Indentation**: 4 spaces (no tabs)
- **Line length**: 120 characters maximum
- **Braces**: End-of-line style
- **Javadoc**: Required for all public APIs

#### Formatting Commands
```bash
# Format all code
mvn formatter:format

# Validate formatting (runs automatically during build)
mvn formatter:validate
```

**Note**: The build will fail if code is not properly formatted. Always run `mvn formatter:format` before committing.

### License Headers
All Java files must include an [SPDX-compliant](https://spdx.dev/learn/handling-license-info/) license header:

```java
/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2025 Codarama
 */
```

### Testing Requirements
- **Unit tests** for all new functionality
- **Integration tests** for Redis interactions
- **Test coverage** should not decrease
- Use **Testcontainers** for integration tests
- Follow **AAA pattern** (Arrange, Act, Assert)

## 🔄 Pull Request Process

### Before Submitting
1. **Rebase** your branch on the latest `main`
2. **Run all tests** locally
3. **Check code coverage** doesn't decrease
4. **Update documentation** if needed
5. **Add/update tests** for new features

### PR Requirements
- **Clear description** of changes
- **Link to issue** if applicable
- **Tests pass** on all platforms
- **No merge conflicts**
- **Signed commits** (optional but recommended)

### Automated Checks
When you submit a PR, our automation will:

1. **Validate** the PR with comprehensive testing
2. **Comment** with detailed results
3. **Run security scans**
4. **Check code style** and license headers
5. **Generate coverage reports**

## 🏗️ Development Setup

### Prerequisites
- **Java 8+** (we test against 8, 11, 17, 21)
- **Maven 3.6+**
- **Docker** (for integration tests)
- **Git**

### Environment Variables
For local development:
```bash
# Optional: Disable Testcontainers Ryuk for faster tests
export TESTCONTAINERS_RYUK_DISABLED=true

# Optional: Use specific Docker host
export DOCKER_HOST=unix:///var/run/docker.sock
```

## 🐛 Bug Reports

When reporting bugs:
1. **Search existing issues** first
2. **Use the bug report template**
3. **Include reproduction steps**
4. **Provide environment details**
5. **Add relevant logs/stack traces**

## 💡 Feature Requests

For new features:
1. **Check existing issues** and discussions
2. **Describe the use case** clearly
3. **Explain the proposed solution**
4. **Consider backward compatibility**
5. **Discuss implementation approach**

## 📚 Documentation

Help improve documentation:
- **README.md** - Main project documentation
- **Javadoc** - API documentation
- **Code comments** - Explain complex logic
- **Examples** - Usage examples and tutorials

## 🎯 Areas for Contribution

We welcome contributions in:
- **Bug fixes** and stability improvements
- **Performance optimizations**
- **Additional Redis driver support**
- **Documentation improvements**
- **Test coverage expansion**
- **Example applications**

## 🤝 Code of Conduct

- Be **respectful** and **inclusive**
- **Help others** learn and grow
- **Focus on constructive feedback**
- **Collaborate** effectively
- **Have fun** building great software!

## 📞 Getting Help

- **GitHub Issues** - For bugs and feature requests
- **GitHub Discussions** - For questions and ideas
- **Code Review** - Learn from PR feedback


Thank you for contributing to Redlock4j! 🚀

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

### Local Testing
Before submitting a PR, run the tests locally:

```bash
# Run all tests
mvn test

# Run only unit tests
mvn test -Dtest=RedlockConfigurationTest

# Run only integration tests (requires Docker)
mvn test -Dtest=RedlockIntegrationTest

# Run with coverage
mvn test jacoco:report

# Security scan
mvn org.owasp:dependency-check-maven:check
```

### Automated Testing
Our CI/CD pipeline automatically runs:

- ✅ **Compilation** on multiple platforms (Ubuntu, Windows, macOS)
- ✅ **Unit tests** with Java 8, 11, 17, and 21
- ✅ **Integration tests** with Testcontainers (Redis 6, 7, latest)
- ✅ **Code coverage** analysis with JaCoCo
- ✅ **Security scanning** with OWASP dependency check
- ✅ **License header** validation
- ✅ **Code style** checks

## 📝 Code Guidelines

### Code Style
- Use **4 spaces** for indentation (no tabs)
- Follow **Java naming conventions**
- Add **Javadoc** for public APIs
- Keep **line length** under 120 characters
- Remove **trailing whitespace**

### License Headers
All Java files must include the MIT license header:

```java
/*
 * MIT License
 *
 * Copyright (c) 2025 Codarama
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
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

### IDE Setup
We recommend:
- **IntelliJ IDEA** or **Eclipse**
- **Checkstyle** plugin for code style
- **SonarLint** for code quality

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

## 🏆 Recognition

Contributors are recognized in:
- **GitHub contributors** list
- **Release notes** for significant contributions
- **Documentation** acknowledgments

Thank you for contributing to Redlock4j! 🚀

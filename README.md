# BBRun

> HTTP API testing DSL - write expressive, readable API tests

[![Release](https://img.shields.io/github/v/release/STRIMS-AB/bbrun)](https://github.com/STRIMS-AB/bbrun/releases)
[![License](https://img.shields.io/github/license/STRIMS-AB/bbrun)](LICENSE)

## Installation

### macOS (Homebrew)

```bash
brew tap STRIMS-AB/tap
brew install bbrun
```

### Linux / macOS (Script)

```bash
curl -fsSL https://raw.githubusercontent.com/STRIMS-AB/bbrun/main/dist/install.sh | bash
```

### Manual Download

Download the latest release from [GitHub Releases](https://github.com/STRIMS-AB/bbrun/releases).

## Quick Start

Create a file `test.bbrun`:

```
baseUrl "https://httpbin.org"

// Simple GET request
get #anything

assert response.status == 200
print "Success!"
```

Run it:

```bash
bbrun test.bbrun
```

## Features

- **Clean syntax** - No boilerplate, just tests
- **Path shorthand** - `#users/123` instead of full URLs
- **Built-in functions** - `uuid()`, `random()`, `randomString()`, `env()`
- **Assertions** - `assert`, `warn`, type checks
- **Variables** - `let id = uuid()`
- **JSON literals** - `{ "key": "value" }`

## Example

```
baseUrl env("API_URL", "https://api.example.com")

// Create a user
let user = {
    "name": "Test User",
    "email": "${randomString(8)}@test.com"
}

post user to #users
let userId = response.body.id

// Verify creation
get #users/${userId}
assert response.body.name == "Test User"

// Cleanup
delete #users/${userId}
```

## Documentation

See the [Language Specification](spec/README.md) for full documentation.

## Building from Source

```bash
git clone https://github.com/STRIMS-AB/bbrun.git
cd bbrun
./gradlew :bbrun-cli:installDist
./bbrun-cli/build/install/bbrun-cli/bin/bbrun-cli --help
```

## License

MIT © [STRIMS AB](https://strims.se)

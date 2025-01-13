# V2GC (Vercel to Git Commit)

A Kotlin-based tool that fetches source files from Vercel deployments and automatically commits them to Git repositories.

## Features

- Fetch source files from Vercel deployments using Vercel REST API
- Automatically commit and push changes to Git repositories
- Configurable deployment selection and Git commit settings
- Secure credential management
- Asynchronous operations with Kotlin Coroutines

## Technology Stack

- **Language**: Kotlin
- **Build Tool**: Gradle with Kotlin DSL
- **HTTP Client**: Ktor Client
  - Content Negotiation
  - JSON Serialization
  - Logging
  - Configuration
- **Git Operations**: JGit
- **Async Programming**: Kotlin Coroutines

## Architecture

### Project Structure
```
src/
├── main/
│   ├── kotlin/
│   │   ├── client/
│   │   │   ├── VercelClient.kt     # Vercel REST API client
│   │   │   └── GitClient.kt        # Git operations wrapper
│   │   ├── model/
│   │   │   ├── VercelDeployment.kt # Data classes for Vercel API
│   │   │   └── Config.kt           # Configuration data classes
│   │   ├── service/
│   │   │   ├── DeploymentService.kt # Business logic for deployments
│   │   │   └── GitService.kt        # Business logic for git operations
│   │   └── Application.kt           # Main application entry point
│   └── resources/
│       └── application.conf         # Configuration file
```

### Core Components

1. **Vercel Client**
   - Handles communication with Vercel REST API
   - Manages authentication and rate limiting
   - Downloads deployment source files

2. **Git Client**
   - Manages Git repository operations
   - Handles commits and pushes
   - Authentication with remote repositories

3. **Service Layer**
   - Business logic for deployment processing
   - File system operations
   - Git operations orchestration

4. **Configuration Management**
   - Environment-based configuration
   - Secure credentials handling
   - Deployment settings

## Setup

### Prerequisites

- JDK 11 or later
- Gradle 7.x or later
- Vercel API Token
- GitHub Personal Access Token

### Configuration

1. Create a `.env` file in the project root:
```env
VERCEL_TOKEN=your_vercel_token
GITHUB_TOKEN=your_github_token
```

2. Configure application settings in `application.conf`:
```hocon
vercel {
    apiUrl = "https://api.vercel.com"
    teamId = "optional_team_id"
}

git {
    authorName = "Your Name"
    authorEmail = "your.email@example.com"
    defaultBranch = "main"
}
```

## Usage

1. Build the project:
```bash
./gradlew build
```

2. Run the application:
```bash
./gradlew run --args="--deployment-id=your_deployment_id"
```

## Development

### Building

```bash
./gradlew clean build
```

### Testing

```bash
./gradlew test
```

### Contributing

1. Fork the repository
2. Create your feature branch
3. Commit your changes
4. Push to the branch
5. Create a Pull Request

## License

This project is licensed under the MIT License - see the LICENSE file for details. 
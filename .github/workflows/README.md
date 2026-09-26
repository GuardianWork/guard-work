# Continuous Integration Workflows

This directory contains the GitHub Actions workflow specifications for the **GuardWork** project.

## Workflow Overview: `ci.yml`

The `ci.yml` pipeline automates testing, packaging, and artifact retention for backend code changes.

### How to use
- **Push**: Any code push to the `dev` branch.
- **Pull Request**: Any pull request targeting the `main` or `dev` branches.

---

## Flowchart Diagram

```mermaid
flowchart TD
    %% Triggers & Events
    subgraph Triggers ["Git Triggers"]
        Developer([Developer]) -->|Push to dev / PR| Repo["GitHub Repository (guard-work)"]
    end

    %% GitHub Actions Runner Environment
    subgraph Runner ["GitHub Actions Runner (ubuntu-latest)"]
        direction TB


        %% Workflow Steps
        subgraph Pipeline ["Job: test-and-build"]
            Step1["Step 1: Checkout Repository\n(actions/checkout@v4)"]
            Step2["Step 2: Set up JDK 25 & Cache\n(actions/setup-java@v4, temurin)"]
            Step3["Step 3: Grant Executable Permission\n(chmod +x backend/mvnw)"]
            Step4["Step 4: Run Backend Tests\n(./mvnw test)"]
            Step5["Step 5: Build Package\n(./mvnw package -DskipTests)"]
            Step6["Step 6: Upload Build Artifact\n(actions/upload-artifact@v4)"]
        end
    end

    %% Storage & Artifacts
    subgraph Storage ["Artifact Retention"]
        Artifacts[("GitHub Artifact Storage\n(gw-backend-jar)")]
    end

    %% Connections
    Repo -->|Triggers ci.yml| Step1
    Step1 --> Step2
    Step2 --> Step3
    Step3 --> Step4
    Step4 -->|Tests Passed| Step5
    Step5 --> Step6
    Step6 -->|7-day retention| Artifacts

    %% Styling
    classDef primary fill:#1f2937,stroke:#3b82f6,stroke-width:2px,color:#fff
    classDef success fill:#064e3b,stroke:#10b981,stroke-width:2px,color:#fff
    classDef dbStyle fill:#1e1b4b,stroke:#6366f1,stroke-width:2px,color:#fff

    class Step1,Step2,Step3,Step5 primary
    class Step4,Step6 success
    class Postgres,Artifacts dbStyle
```


## Step Summary

| Step # | Action Name | Command / Action | Description |
| :---: | :--- | :--- | :--- |
| **1** | Checkout Repository | `actions/checkout@v4` | Fetches repository source code into the runner workspace. |
| **2** | Set up JDK 25 | `actions/setup-java@v4` | Installs JDK 25 (Temurin) and enables Maven dependency caching. |
| **3** | Grant execute permission | `chmod +x backend/mvnw` | Ensures the Maven wrapper script has executable flags. |
| **4** | Run Backend Tests | `./mvnw test` | Runs unit and database integration tests in `backend/`. |
| **5** | Build Application Package | `./mvnw package -DskipTests` | Compiles source code into executable JAR (`backend/target/*.jar`). |
| **6** | Upload Build Artifact | `actions/upload-artifact@v4` | Retains `gw-backend-jar` in GitHub storage for 7 days. |

## Future Roadmap
+ Add **SonarQube** server (for code analysis)
+ Add deployment stage with **Render**
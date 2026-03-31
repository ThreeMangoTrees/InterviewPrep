# Docker — Interview Prep

## What is Docker?
Docker is a platform used to build, package, ship, and run applications inside **containers**.

A container is a lightweight, portable unit that includes:
- the application code
- runtime
- system libraries
- dependencies
- configuration needed to run the application

The main idea is:
**build once, run anywhere**.

Unlike a traditional virtual machine, Docker containers share the host OS kernel, so they are much lighter and faster to start.

---

## How is Docker used?
Docker is commonly used in software development, testing, deployment, and production operations.

Typical usage flow:
1. Write application code
2. Write a `Dockerfile`
3. Build a Docker image from that `Dockerfile`
4. Run the image as a container
5. Push the image to a registry like Docker Hub, AWS ECR, or Azure Container Registry
6. Pull and run the same image in different environments

Common use cases:
- local development consistency
- CI/CD pipelines
- microservices deployment
- testing in isolated environments
- packaging backend services, databases, workers, and APIs
- running the same app across developer laptops, staging, and production

Example:
A Java Spring Boot app can be packaged into a Docker image and then run the same way on a Mac laptop, Linux VM, or cloud server.

---

## What is a Dockerfile?
A `Dockerfile` is a text file that contains instructions for building a Docker image.

It defines:
- base image
- files to copy
- dependencies to install
- commands to run
- ports to expose
- startup command

In short:
A `Dockerfile` is the blueprint for creating a Docker image.

Example:
```dockerfile
FROM openjdk:17
WORKDIR /app
COPY target/myapp.jar app.jar
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
```

This tells Docker:
- start from an OpenJDK 17 base image
- set `/app` as working directory
- copy the JAR file
- expose port 8080
- run the JAR when the container starts

---

## How is a Dockerfile created?
A Dockerfile is created manually as a plain text file named exactly:

```text
Dockerfile
```

No file extension is required.

Steps:
1. Choose a base image
2. Define working directory
3. Copy source code or build artifacts
4. Install dependencies if needed
5. Expose the application port
6. Define the startup command

Typical instructions used in a Dockerfile:
- `FROM` → base image
- `WORKDIR` → working directory inside container
- `COPY` → copy files into image
- `RUN` → execute commands while building image
- `EXPOSE` → document container port
- `ENV` → set environment variables
- `CMD` → default startup command
- `ENTRYPOINT` → main executable for the container

Example for a Python app:
```dockerfile
FROM python:3.11
WORKDIR /app
COPY requirements.txt .
RUN pip install -r requirements.txt
COPY . .
EXPOSE 8000
CMD ["python", "app.py"]
```

Build command:
```bash
docker build -t myapp .
```

Run command:
```bash
docker run -p 8000:8000 myapp
```

---

## What problem does Docker solve?
Docker solves the classic problem:

**"It works on my machine, but not on another machine."**

Main problems Docker solves:

### 1. Environment inconsistency
Different machines may have different:
- OS versions
- runtime versions
- installed libraries
- configuration

Docker packages everything needed so the app runs consistently.

### 2. Dependency conflicts
Two applications may need different versions of the same dependency.
Docker isolates them in separate containers.

### 3. Deployment complexity
Instead of manually installing software on every server, teams can deploy the same Docker image everywhere.

### 4. Poor portability
Docker makes applications portable across environments.

### 5. Resource overhead of VMs
Compared to full virtual machines, containers are lighter, faster, and more efficient.

### 6. Scaling microservices
Docker makes it easier to package and run many small independent services.

---

## How is Docker different from Kubernetes?
This is a very common interview question.

### Docker
Docker is primarily used to:
- build container images
- package applications
- run containers

It answers:
**How do I containerize and run an application?**

### Kubernetes
Kubernetes is primarily used to:
- orchestrate containers at scale
- manage deployment, scaling, networking, and recovery of containers across many machines

It answers:
**How do I manage many containers across many servers in production?**

### Simple comparison
- Docker = containerization tool
- Kubernetes = container orchestration platform

### Example
If you have one container running on your laptop, Docker is enough.

If you have hundreds of containers across many servers and want:
- auto-scaling
- self-healing
- rolling deployments
- service discovery
- load balancing

then Kubernetes is used.

### Key difference
Docker deals with **creating and running containers**.  
Kubernetes deals with **managing containers across a cluster**.

Note:
In modern setups, Kubernetes usually uses a container runtime underneath, and Docker itself is not the orchestrator.

---

## What are the advantages of Docker?

### 1. Portability
The same container can run across different environments consistently.

### 2. Lightweight
Containers share the host OS kernel, so they use fewer resources than VMs.

### 3. Fast startup
Containers usually start much faster than virtual machines.

### 4. Better developer productivity
Developers can quickly spin up environments and avoid setup issues.

### 5. Dependency isolation
Each application can have its own libraries and runtime versions.

### 6. Good fit for microservices
Each service can be packaged independently.

### 7. Easier CI/CD integration
Docker images are easy to build, test, version, and deploy in pipelines.

### 8. Scalability support
Docker works well with orchestration tools for scaling applications.

### 9. Reproducible builds
Images provide a repeatable way to package software.

### 10. Better utilization than VMs
Containers are more efficient than full VM-based deployments for many workloads.

---

## What are the disadvantages of Docker?

### 1. Not full OS isolation like VMs
Containers share the host kernel, so isolation is weaker than virtual machines.

### 2. Security concerns
If misconfigured, containers can expose risks such as privilege escalation or insecure images.

### 3. Persistent storage is harder
Containers are ephemeral by design, so stateful apps need careful volume/storage handling.

### 4. Networking can become complex
Multi-container communication, service discovery, and production networking can get tricky.

### 5. Learning curve
Docker concepts like images, containers, volumes, networks, and registries take time to learn properly.

### 6. Debugging can be harder
Containerized systems can add another layer of complexity during troubleshooting.

### 7. Image bloat
Poorly written Dockerfiles can create very large images.

### 8. Orchestration still needed at scale
Docker alone is not enough for large-scale production systems.

### 9. OS kernel dependency
Linux containers generally expect a Linux kernel; cross-OS behavior is not identical in all cases.

### 10. Operational sprawl
Too many images, versions, and containers can become hard to manage without discipline.

---

## Short interview-ready answer
Docker is a containerization platform used to package an application along with its dependencies so it runs consistently across environments. It solves environment mismatch, dependency conflicts, and deployment inconsistency. A Dockerfile is the instruction file used to build a Docker image. Docker is different from Kubernetes because Docker focuses on creating and running containers, while Kubernetes focuses on orchestrating containers across many servers. Docker’s main advantages are portability, lightweight execution, and consistency, while its disadvantages include security considerations, storage complexity for stateful apps, and limited orchestration by itself.

---

## One-line summary
Docker packages software into lightweight, portable containers so applications run consistently across machines and environments.

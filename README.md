# Multithreaded TCP File Sharing Server & Client

A high-performance, concurrent, pure Java 17 TCP file-sharing server and client implementation built strictly with standard JDK utilities (no external third-party dependencies).

The system supports multi-client authentication, streaming file uploads, seekable resumable downloads, directory file listing, granular per-filename concurrency control, path traversal protection, and thread-safe persistent logging.

---

## Key Features

- **Multithreaded Executor Architecture**: Uses a fixed thread pool (`ExecutorService` with 8 worker threads) to handle up to 8 concurrent clients simultaneously while bounding stack memory allocation.
- **Granular Per-Filename Locking**: Employs `ConcurrentHashMap` + `ReentrantLock` to enable parallel operations on distinct files while serializing reads/writes to the *same* file to prevent byte corruption.
- **TOCTOU Race Condition Prevention**: Consolidates file size queries, protocol header generation (`RESP_OK` + length), and stream delivery into single atomic lock blocks.
- **Seekable & Resumable Downloads**: Uses `RandomAccessFile.seek()` on the server side and append-mode file streams (`FileOutputStream(file, true)`) on the client side to resume interrupted downloads seamlessly without re-transferring existing bytes.
- **Path Traversal Security**: Implements double-layered validation using `Paths.get().getFileName()` and absolute normalized path verification (`startsWith(storagePath)`) to block directory traversal attacks (`../`).
- **Protocol Synchronization Draining**: Reads and discards promised byte streams (`drainBytes`) when an upload is rejected (e.g., unauthenticated client), keeping TCP socket frames synchronized.
- **Thread-Safe Structured Logging**: Dual-output timestamped logging (`logs/server.log` and stdout) using synchronized logger instances to prevent interleaved log outputs.

---

## System Architecture

```
                               ┌──────────────────────────────────────────────┐
                               │                 Server.java                  │
                               │  - Main accept loop on Port 5000             │
                               │  - Fixed Thread Pool (8 Worker Threads)      │
                               └──────────────────────┬───────────────────────┘
                                                      │
                                    serverSocket.accept() returns Socket
                                                      │
                                                      ▼
                               ┌──────────────────────────────────────────────┐
                               │           ExecutorService Pool Queue         │
                               └──────────────────────┬───────────────────────┘
                                                      │
                                  Pool worker thread picks up ClientHandler
                                                      │
          ┌───────────────────────────────────────────┼───────────────────────────────────────────┐
          ▼                                           ▼                                           ▼
┌──────────────────┐                        ┌──────────────────┐                        ┌──────────────────┐
│ ClientHandler 1  │                        │ ClientHandler 2  │                        │ ClientHandler 3  │
│ - Session A      │                        │ - Session B      │                        │ - Session C      │
└─────────┬────────┘                        └─────────┬────────┘                        └─────────┬────────┘
          │                                           │                                           │
          └───────────────────────────────────────────┼───────────────────────────────────────────┘
                                                      │
                                                      ▼
                               ┌──────────────────────────────────────────────┐
                               │              FileService.java                │
                               │  - ConcurrentHashMap<String, ReentrantLock>  │
                               │  - Double-Layer Path Traversal Check         │
                               └──────────────────────┬───────────────────────┘
                                                      │
                                                      ▼
                               ┌──────────────────────────────────────────────┐
                               │              LoggerService.java              │
                               │  - Synchronized Atomic Write                 │
                               │  - Appends to logs/server.log & stdout       │
                               └──────────────────────────────────────────────┘
```

---

## Custom Protocol Specification

All communication between client and server occurs over TCP using typed Data Streams (`DataInputStream` / `DataOutputStream`). Every client request starts with a UTF command string.

| Command | Client Request Payload | Server Response Payload | Description |
| :--- | :--- | :--- | :--- |
| **`LOGIN`** | `readUTF(cmd)` + `readUTF(user)` + `readUTF(pass)` | `RESP_OK` or `ERROR:<reason>` | Authenticates against `users.txt`. Sets `Session` on connection handler. |
| **`UPLOAD`** | `readUTF(cmd)` + `readUTF(filename)` + `readLong(size)` + `byte[size]` | `RESP_OK` or `ERROR:<reason>` | Uploads file. Drains bytes if unauthenticated. Serializes access via file lock. |
| **`DOWNLOAD`**| `readUTF(cmd)` + `readUTF(filename)` + `readLong(offset)` | `RESP_OK` + `readLong(remainingBytes)` + `byte[remainingBytes]` or `ERROR:<reason>` | Downloads file from specified offset ($O(1)$ seek). Supports partial resumes. |
| **`LIST`** | `readUTF(cmd)` | `RESP_OK` + `readInt(count)` + $N \times$ `readUTF(filename)` or `ERROR:<reason>` | Returns a sorted array of regular files stored in the server storage directory. |
| **`EXIT`** | `readUTF(cmd)` | `RESP_OK` | Gracefully closes the client handler connection and releases socket descriptors. |

---

## Project Structure

```
c:\fileupload\
├── common/
│   └── Protocol.java          # Shared constants, ports, buffer size (8 KB), and protocol helpers
├── server/
│   ├── Server.java            # Server entry point: accepts connections & submits to ThreadPool
│   ├── ClientHandler.java     # Runnable connection lifecycle & command dispatch loop
│   ├── AuthService.java       # Credential loader (users.txt) & ConcurrentHashMap authentication
│   ├── FileService.java       # Per-filename ReentrantLock control, I/O streaming, and listing
│   ├── LoggerService.java     # Thread-safe synchronized timestamp logger (AutoCloseable)
│   └── Session.java           # Thread-isolated session holder
├── client/
│   └── Client.java            # Console UI client with interactive menu and auto-resume support
├── users.txt                  # User credentials database (username:password)
├── storage/                   # Server-side file storage directory
└── logs/
    └── server.log             # Server action audit logs
```

---

## Getting Started

### Prerequisites
- **JDK 17** or higher installed and configured in your environment path.

### Compilation

Compile all Java packages (`common`, `server`, `client`) into an `out/` directory:

```powershell
javac -d out common/Protocol.java server/AuthService.java server/Session.java server/FileService.java server/LoggerService.java server/ClientHandler.java server/Server.java client/Client.java
```

### Running the Server

Start the server process on port 5000:

```powershell
java -cp out server.Server
```

*Default credentials stored in `users.txt`:* `alice` / `password123`.

### Running the Client

Open one or more terminal windows and launch the client:

```powershell
java -cp out client.Client
```

#### Client Interactive Menu
```text
+------------------------------+
|     FILE SHARING CLIENT      |
+------------------------------+
|  1) Login                    |
|  2) Upload File              |
|  3) Download File            |
|  4) List Files               |
|  5) Exit                     |
+------------------------------+
Choose an option:
```

---

## Technical Deep Dive

### 1. Per-Filename Locking & Deadlock Immunity
Instead of global synchronization on `FileService` (which would bottleneck parallel uploads of distinct files), locks are allocated on a **per-filename basis**:

```java
private final Map<String, ReentrantLock> fileLocks = new ConcurrentHashMap<>();

private ReentrantLock getLock(String filename) {
    return fileLocks.computeIfAbsent(filename, key -> new ReentrantLock());
}
```

Because file operations acquire at most **one lock** at any given moment, circular wait conditions cannot occur, guaranteeing **deadlock immunity**.

### 2. Double-Layer Path Traversal Protection
Path inputs are untrusted and could contain traversal patterns (`../`). `FileService` enforces two defense layers:

1. **Filename Component Extraction**: Extracts only the trailing base name via `Paths.get(filename).getFileName()`.
2. **Absolute Containment Check**: Resolves, normalizes, and verifies that `targetPath.startsWith(storagePath)` is true, throwing `SecurityException` on violations.

### 3. Seekable Resume Download Flow
1. Client checks if a local partial file exists. If found, it retrieves its length $N$ bytes and sets `offset = N`.
2. Server validates $0 \le \text{offset} \le \text{totalSize}$.
3. Server uses `RandomAccessFile.seek(offset)` to jump directly to byte $N$ in $O(1)$ time and streams the remaining bytes.
4. Client opens `FileOutputStream(file, true)` in **append mode** to write incoming bytes without truncating existing content.

---

## License

This project is created for educational, architectural demonstration, and interview preparation purposes.

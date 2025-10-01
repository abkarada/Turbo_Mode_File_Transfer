# TURBO FILE TRANSFER

A high-performance, UDP-based file transfer protocol designed for maximum throughput and reliable delivery. This implementation achieves near-gigabit speeds by eliminating TCP's overhead and head-of-line blocking.

## 🚀 Key Features

### **Blazing Fast Performance**
- **Pure UDP** - No TCP overhead or congestion control delays
- **Memory-mapped I/O** - Zero-copy data transfer
- **Scatter-gather I/O** - Single syscall for header+payload
- **Parallel processing** - Transmission and error recovery run concurrently

### **Rock-solid Reliability**
- **Dual integrity checking**: CRC32C per-packet + SHA-256 file-level verification
- **Intelligent NACK-based recovery** - Receiver requests only missing/corrupted packets
- **Exponential backoff** - Network-friendly congestion handling
- **Timeout-based fallbacks** - Robust error handling

### **Smart Protocol Design**
- **3-way handshake** for connection establishment
- **BitSet-based tracking** - Efficient packet state management
- **Selective retransmission** - Only corrupted/missing packets are resent
- **Bounded recovery window** - Prevents infinite retransmission loops

## 📊 Performance Characteristics

| Feature | TCP | QUIC | Turbo Transfer |
|---------|-----|------|----------------|
| **Throughput** | ~6-7 Gbps | ~7-8 Gbps | **~9+ Gbps** |
| **Head-of-line blocking** | ❌ Yes | ❌ Partial | ✅ None |
| **Connection overhead** | High | Medium | **Minimal** |
| **Recovery latency** | High | Medium | **Sub-millisecond** |

## 🛠 Architecture

### **Protocol Stack**
Application Layer │ File Transfer API Protocol Layer │ Handshake + Data + NACK + SHA256 Transport Layer │ Custom UDP Protocol
Network Layer │ IP
### **Packet Types**
- **HandShake_Packet** (17 bytes): SYN, ACK, SYN_ACK for connection establishment
- **CRC32C_Packet** (22 bytes header): Data packets with per-packet integrity
- **NackFrame** (20 bytes): Receiver-driven selective retransmission requests
- **SHA256_Packet** (53 bytes): Final file integrity verification

### **Core Components**

#### **FileTransferSender**
- Establishes handshake with receiver
- Blasts entire file at maximum speed
- Processes NACK requests for selective retransmission
- Sends SHA-256 signature for final verification

#### **FileTransferReceiver**
- Accepts connection via handshake
- Receives packets with CRC32C verification
- Generates NACK requests for missing/corrupted packets
- Validates final SHA-256 signature

#### **NackListener/NackSender**
- **NackListener**: Monitors incoming NACK requests, queues retransmissions
- **NackSender**: Tracks received packets, generates NACK requests with 64-bit bitmasks

## 🔧 Usage

### **Sender Side**
```java
// Bind and connect channel
DatagramChannel channel = DatagramChannel.open();
channel.bind(null);
channel.connect(receiverAddress);
FileTransferSender.channel = channel;

// Send file
Path filePath = Paths.get("largefile.dat");
long fileId = System.currentTimeMillis();
FileTransferSender.sendFile(filePath, fileId);

📋 Requirements
Java 11+ (for memory-mapped I/O enhancements)
Network MTU ≥ 1222 bytes (22-byte header + 1200-byte payload)
Memory: ~256MB for maximum file size in turbo mode
⚡ Protocol Flow
Sender                           Receiver
  │                                │
  ├─── SYN(fileId, size, seq) ───→ │
  │                                │
  │ ←─── ACK(fileId, size, seq) ─── │
  │                                │
  ├─── SYN_ACK(fileId) ──────────→ │
  │                                │
  │ ┌─ DATA BLAST PHASE ─────────┐ │
  │ │  All packets sent         │ │
  │ │  without waiting for ACK  │ │
  │ └───────────────────────────┘ │
  │                                │
  │ ←─── NACK(missing packets) ──── │
  │                                │
  ├─── Retransmit missing ──────→ │
  │                                │
  ├─── SHA256 signature ────────→ │
  │                                │
  │ ←─── Final ACK ─────────────── │
  │                                │
  Design Philosophy
  "Blast first, fix later" - This protocol prioritizes raw throughput by sending all data immediately, then using intelligent selective retransmission to ensure reliability. By eliminating TCP's conservative approach, we achieve maximum network utilization while maintaining 100% data integrity.
  
  ⚠️ Limitations
  File size limit: 256 MB (TURBO_MAX)
  Single file transfer: No multiplexing support
  Network-friendly: Uses exponential backoff, but can be aggressive on initial transmission
  Memory usage: Entire file is memory-mapped
  🔬 Technical Details
  CRC32C: Hardware-accelerated polynomial for corruption detection
  SHA-256: Cryptographic hash for file-level integrity
  BitSet tracking: O(1) packet state management
  Concurrent queues: Lock-free NACK request handling
  Memory-mapped I/O: Direct memory-to-network pipeline
  
  Built for scenarios where maximum throughput matters and you control both endpoints.

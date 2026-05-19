Media Encoding System Design (Netflix L4)

⸻

Q1: Clarify Requirements

Functional
	•	Input: Raw video + metadata (audio tracks, subtitles, priority)
	•	Output: Multi-resolution, multi-codec encoded assets + manifests
	•	Encoding types:
	•	Resolutions: 240p → 4K
	•	Codecs: H264, H265, AV1
	•	Nature: Asynchronous batch pipeline (not real-time)

Non-Functional
	•	Scale: Millions of jobs/day, up to 100GB+ per video
	•	Latency: Hours (standard), minutes (urgent)
	•	Fault tolerance: No data loss, retries, partial recovery, idempotency

⸻

Q2: High-Level Architecture

Flow:
Ingest → Orchestrator → Queue → Workers → Packaging → Storage/CDN → Metadata

Components:
	•	Ingest Service: Upload + validation
	•	Orchestrator: Generates execution plan
	•	Queue: Decouples system (Kafka/SQS)
	•	Workers: Encode segments
	•	Packaging: Create manifests
	•	Metadata Store: Tracks state
	•	Storage/CDN: Stores and serves content

⸻

Q3: Splitting a 100GB Video

Strategy
	1.	Rendition-level split
	2.	Segment-level split (GOP aligned)

Benefits
	•	Parallelism
	•	Faster retries
	•	Better utilization

Scheduling
	•	Priority queues (P0, P1, P2)
	•	Capability-aware workers (CPU/GPU)
	•	Controlled fanout per job

⸻

Q4: Metadata Store & State Machine

Entities
	•	Job
	•	Rendition
	•	Segment
	•	Attempt

State Hierarchy

Job → Rendition → Segment

Key Features
	•	Lease-based execution
	•	Idempotent updates
	•	Retry tracking
	•	Event/audit logs

⸻

Q5: Handling 10x Backlog Spike

Immediate
	•	Priority scheduling
	•	Backpressure
	•	Retry throttling

Scaling
	•	Add workers based on bottleneck
	•	Avoid overloading storage/network

Degradation
	•	Skip optional renditions
	•	Delay expensive codecs

Recovery
	•	Drain high priority first
	•	Gradual recovery

⸻

Q6: CPU vs GPU for Encoding

CPU Encoding

Use when:
	•	H264 standard encoding
	•	Lower resolutions (≤1080p)
	•	Cost-sensitive workloads
	•	Flexible scaling needed

Pros:
	•	Cheap
	•	Easy to scale

Cons:
	•	Slower for heavy workloads

GPU Encoding

Use when:
	•	4K / high-resolution
	•	HEVC / AV1 (compute heavy)
	•	Real-time or urgent workloads

Pros:
	•	Much faster
	•	High throughput

Cons:
	•	Expensive
	•	Limited availability

Strategy
	•	Hybrid model
	•	Route tasks based on:
	•	codec
	•	resolution
	•	urgency

⸻

Concepts Explained

GOP (Group of Pictures)

A sequence of video frames starting with a keyframe.
	•	Used for splitting video safely
	•	Ensures independent decoding of segments

Checksum

A hash of data used to verify integrity.
	•	Ensures encoded output is not corrupted

Rendition

A specific output version of a video.
	•	Example: 720p H264

Manifest

A metadata file describing video streams.
	•	Used by players to select bitrate (HLS/DASH)

Packaging

Process of assembling encoded segments into playable format.
	•	Includes manifest generation

Version Number / CAS (Compare-And-Swap)

Concurrency control mechanism.
	•	Update only if version matches
	•	Prevents race conditions

Idempotency

Same operation can run multiple times safely.
	•	Prevents duplicate processing issues

⸻

Final Summary
	•	Event-driven pipeline
	•	Hierarchical task breakdown
	•	Strong metadata tracking
	•	Priority-based scheduling
	•	Hybrid CPU/GPU execution
	•	Designed for scale, resilience, and cost-efficiency
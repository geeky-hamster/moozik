#pragma once

#include <algorithm>
#include <atomic>
#include <cstring>
#include <cstddef>

namespace moozik {

// Lock-free SPSC ring buffer over interleaved float samples.
// Positions are unbounded size_t counters; unsigned subtraction stays valid across wrap.
class RingBuffer {
public:
    explicit RingBuffer(size_t capacityFloats)
        : data_(new float[capacityFloats]), capacity_(capacityFloats) {}

    ~RingBuffer() { delete[] data_; }

    size_t capacity() const { return capacity_; }

    size_t size() const {
        const size_t w = writePos_.load(std::memory_order_acquire);
        const size_t r = readPos_.load(std::memory_order_relaxed);
        return w - r;
    }

    void abort() { aborted_.store(true, std::memory_order_release); }
    void resetAbort() { aborted_.store(false, std::memory_order_release); }
    bool aborted() const { return aborted_.load(std::memory_order_acquire); }

    /// Producer side. Returns number of floats actually written.
    size_t write(const float* src, size_t count) {
        size_t done = 0;
        while (done < count) {
            const size_t w = writePos_.load(std::memory_order_relaxed);
            const size_t r = readPos_.load(std::memory_order_acquire);
            const size_t freeSpace = capacity_ - 1 - (w - r);
            if (freeSpace == 0 || aborted_.load(std::memory_order_acquire)) break;

            size_t n = std::min(count - done, freeSpace);
            const size_t pos = w % capacity_;
            n = std::min(n, capacity_ - pos);

            std::memcpy(data_ + pos, src + done, n * sizeof(float));
            writePos_.store(w + n, std::memory_order_release);
            done += n;
        }
        return done;
    }

    /// Consumer side (real-time thread). Returns number of floats actually read.
    size_t read(float* dst, size_t count) {
        size_t done = 0;
        while (done < count) {
            const size_t w = writePos_.load(std::memory_order_acquire);
            const size_t r = readPos_.load(std::memory_order_relaxed);
            const size_t available = w - r;
            if (available == 0) break;

            size_t n = std::min(count - done, available);
            const size_t pos = r % capacity_;
            n = std::min(n, capacity_ - pos);

            std::memcpy(dst + done, data_ + pos, n * sizeof(float));
            readPos_.store(r + n, std::memory_order_release);
            done += n;
        }
        return done;
    }

private:
    float* data_;
    size_t capacity_;
    alignas(64) std::atomic<size_t> writePos_{0};
    alignas(64) std::atomic<size_t> readPos_{0};
    std::atomic<bool> aborted_{false};
};

} // namespace moozik

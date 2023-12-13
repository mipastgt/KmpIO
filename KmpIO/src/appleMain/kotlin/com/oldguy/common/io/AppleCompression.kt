package com.oldguy.common.io

import kotlinx.cinterop.*
import platform.darwin.*

/**
 * Apple support compression library used to implement compress/decompress operations. This is a common implementation
 * usable by all Apple target platforms
 */
class AppleCompression(override val algorithm: CompressionAlgorithms)
    :Compression
{
    override val bufferSize = 4096

    private val appleConst: compression_algorithm = when (algorithm) {
        CompressionAlgorithms.Deflate -> COMPRESSION_ZLIB
        CompressionAlgorithms.LZMA -> COMPRESSION_LZMA
        else -> throw IllegalArgumentException("Unsupported Apple compression $algorithm")
    }

    override suspend fun compress(input: suspend () -> ByteBuffer,
                         output: suspend (buffer: ByteBuffer) -> Unit
    ): ULong {
        return transform(true, input, output)
    }

    override suspend fun compressArray(input: suspend () -> ByteArray,
                              output: suspend (buffer: ByteArray) -> Unit
    ): ULong {
        return transform(true,
            input = { ByteBuffer(input()) },
            output = { output( it.getBytes()) }
        )
    }

    override suspend fun decompress(
        input: suspend () -> ByteBuffer,
        output: suspend (buffer: ByteBuffer) -> Unit
    ): ULong {
        return transform(false, input, output)
    }


    override suspend fun decompressArray(
        input: suspend () -> ByteArray,
        output: suspend (buffer: ByteArray) -> Unit
    ): ULong {
        return transform(false,
            input = { ByteBuffer(input()) },
            output = { output( it.getBytes()) }
        )
    }

    @OptIn(ExperimentalForeignApi::class)
    private suspend fun transform(
        encode: Boolean,
        input: suspend () -> ByteBuffer,
        output: suspend (buffer: ByteBuffer) -> Unit
    ): ULong {
        var outCount = 0UL
        memScoped {
            val cmp: CValuesRef<compression_stream> = alloc<compression_stream>().ptr
            val code = if (encode) COMPRESSION_STREAM_ENCODE else COMPRESSION_STREAM_DECODE
            val status = compression_stream_init(
                cmp,
                code,
                appleConst
            )
            var count = 0
            if (status == COMPRESSION_STATUS_OK) {
                val inPage = UByteArray(bufferSize)
                var inCount = 0UL
                inPage.usePinned { pinIn ->
                    val outPage = UByteArray(bufferSize)
                    outPage.usePinned { pinOut ->
                        var inBuf = input()
                        inCount += inBuf.remaining.toULong()
                        var sourceLength = inBuf.remaining
                        inBuf.getBytes(sourceLength).toUByteArray().copyInto(inPage)
                        cmp.getPointer(this).pointed.apply {
                            dst_ptr = pinOut.addressOf(0)
                            dst_size = outPage.size.toULong()
                            src_ptr = pinIn.addressOf(0)
                            src_size = sourceLength.toULong()
                            var needsFinal = false
                            var flags = 0
                            while (sourceLength > 0) {
                                val result = compression_stream_process(cmp, flags)
                                count++
                                //println("# $count - in: $inCount, out: $outCount, src: $src_size, dst: $dst_size, result: $result, flag: $flags")
                                when (result) {
                                    COMPRESSION_STATUS_OK -> {
                                        if (src_size == 0UL) {
                                            if (encode) needsFinal = true
                                            inBuf = input()
                                            sourceLength = inBuf.remaining
                                            inCount += sourceLength.toUInt()
                                            if (sourceLength == 0)
                                                flags = COMPRESSION_STREAM_FINALIZE.toInt()
                                            inBuf.getBytes(sourceLength).toUByteArray().copyInto(inPage)
                                            src_ptr = pinIn.addressOf(0)
                                            src_size = sourceLength.toULong()
                                        }
                                        if (dst_size == 0UL) {
                                            output(ByteBuffer(outPage.toByteArray()))
                                            outCount += outPage.size.toULong()
                                            dst_ptr = pinOut.addressOf(0)
                                            dst_size = outPage.size.toULong()
                                        }
                                    }
                                    COMPRESSION_STATUS_END -> {
                                        if (encode) throw IllegalStateException("Compression error - unexpected STATUS_END during encode")
                                        outCount += sink(outPage, dst_size, output)
                                        break
                                    }
                                    COMPRESSION_STATUS_ERROR -> {
                                        throw IllegalStateException("Compression error. Result $result")
                                    }
                                }
                            }
                            if (needsFinal) {
                                val result = compression_stream_process(cmp, COMPRESSION_STREAM_FINALIZE.toInt())
                                if (result == COMPRESSION_STATUS_END) {
                                    outCount += sink(outPage, dst_size, output)
                                } else
                                    throw IllegalStateException("Compression error. Result $result")
                            }
                        }
                    }
                }
            } else
                throw IllegalStateException("Compression init failed")
            compression_stream_destroy(cmp)
        }
        return outCount
    }
}

private suspend fun sink(outPage: UByteArray, dstSize: ULong, output: suspend (buffer: ByteBuffer) -> Unit): UInt {
    val length = outPage.size.toUInt() - dstSize
    if (length > 0u) {
        ByteBuffer(length.toInt()).apply {
            putBytes(outPage.toByteArray(), length = length.toInt())
            flip()
            output(this)
        }
    }
    return length.toUInt()
}
package net.corda.nodeapi.internal.serialization.amqp

import com.esotericsoftware.kryo.io.ByteBufferInputStream
import net.corda.core.internal.VisibleForTesting
import net.corda.core.internal.getStackTraceAsString
import net.corda.core.serialization.EncodingWhitelist
import net.corda.core.serialization.SerializationContext
import net.corda.core.serialization.SerializedBytes
import net.corda.core.utilities.ByteSequence
import net.corda.nodeapi.internal.serialization.CordaSerializationEncoding
import net.corda.nodeapi.internal.serialization.NullEncodingWhitelist
import net.corda.nodeapi.internal.serialization.SectionId
import net.corda.nodeapi.internal.serialization.encodingNotPermittedFormat
import org.apache.qpid.proton.amqp.Binary
import org.apache.qpid.proton.amqp.DescribedType
import org.apache.qpid.proton.amqp.UnsignedByte
import org.apache.qpid.proton.amqp.UnsignedInteger
import org.apache.qpid.proton.codec.Data
import java.io.InputStream
import java.io.NotSerializableException
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType
import java.nio.ByteBuffer

data class ObjectAndEnvelope<out T>(val obj: T, val envelope: Envelope)

/**
 * Main entry point for deserializing an AMQP encoded object.
 *
 * @param serializerFactory This is the factory for [AMQPSerializer] instances and can be shared across multiple
 * instances and threads.
 */
class DeserializationInput @JvmOverloads constructor(private val serializerFactory: SerializerFactory,
                                                     private val encodingWhitelist: EncodingWhitelist = NullEncodingWhitelist) {
    private val objectHistory: MutableList<Any> = mutableListOf()

    companion object {
        private val BYTES_NEEDED_TO_PEEK: Int = 23

        fun peekSize(bytes: ByteArray): Int {
            // There's an 8 byte header, and then a 0 byte plus descriptor followed by constructor
            val eighth = bytes[8].toInt()
            check(eighth == 0x0) { "Expected to find a descriptor in the AMQP stream" }
            // We should always have an Envelope, so the descriptor should be a 64-bit long (0x80)
            val ninth = UnsignedByte.valueOf(bytes[9]).toInt()
            check(ninth == 0x80) { "Expected to find a ulong in the AMQP stream" }
            // Skip 8 bytes
            val eighteenth = UnsignedByte.valueOf(bytes[18]).toInt()
            check(eighteenth == 0xd0 || eighteenth == 0xc0) { "Expected to find a list8 or list32 in the AMQP stream" }
            val size = if (eighteenth == 0xc0) {
                // Next byte is size
                UnsignedByte.valueOf(bytes[19]).toInt() - 3 // Minus three as PEEK_SIZE assumes 4 byte unsigned integer.
            } else {
                // Next 4 bytes is size
                UnsignedByte.valueOf(bytes[19]).toInt().shl(24) + UnsignedByte.valueOf(bytes[20]).toInt().shl(16) + UnsignedByte.valueOf(bytes[21]).toInt().shl(8) + UnsignedByte.valueOf(bytes[22]).toInt()
            }
            return size + BYTES_NEEDED_TO_PEEK
        }

        @VisibleForTesting
        @Throws(NotSerializableException::class)
        fun <T> withDataBytes(byteSequence: ByteSequence, encodingWhitelist: EncodingWhitelist, task: (ByteBuffer) -> T): T {
            // Check that the lead bytes match expected header
            val amqpSequence = amqpMagic.consume(byteSequence)
                    ?: throw NotSerializableException("Serialization header does not match.")
            var stream: InputStream = ByteBufferInputStream(amqpSequence)
            try {
                while (true) {
                    when (SectionId.reader.readFrom(stream)) {
                        SectionId.ENCODING -> {
                            val encoding = CordaSerializationEncoding.reader.readFrom(stream)
                            encodingWhitelist.acceptEncoding(encoding) || throw NotSerializableException(encodingNotPermittedFormat.format(encoding))
                            stream = encoding.wrap(stream)
                        }
                        SectionId.DATA_AND_STOP, SectionId.ALT_DATA_AND_STOP -> return task(stream.asByteBuffer())
                    }
                }
            } finally {
                stream.close()
            }
        }

        @Throws(NotSerializableException::class)
        fun getEnvelope(byteSequence: ByteSequence, encodingWhitelist: EncodingWhitelist = NullEncodingWhitelist): Envelope {
            return withDataBytes(byteSequence, encodingWhitelist) { dataBytes ->
                val data = Data.Factory.create()
                val expectedSize = dataBytes.remaining()
                if (data.decode(dataBytes) != expectedSize.toLong()) throw NotSerializableException("Unexpected size of data")
                Envelope.get(data)
            }
        }
    }


    @Throws(NotSerializableException::class)
    fun getEnvelope(byteSequence: ByteSequence) = Companion.getEnvelope(byteSequence, encodingWhitelist)

    @Throws(NotSerializableException::class)
    inline fun <reified T : Any> deserialize(bytes: SerializedBytes<T>, context: SerializationContext): T =
            deserialize(bytes, T::class.java, context)


    @Throws(NotSerializableException::class)
    private fun <R> des(generator: () -> R): R {
        try {
            return generator()
        } catch (nse: NotSerializableException) {
            throw nse
        } catch (t: Throwable) {
            throw NotSerializableException("Unexpected throwable: ${t.message} ${t.getStackTraceAsString()}")
        } finally {
            objectHistory.clear()
        }
    }

    /**
     * This is the main entry point for deserialization of AMQP payloads, and expects a byte sequence involving a header
     * indicating what version of Corda serialization was used, followed by an [Envelope] which carries the object to
     * be deserialized and a schema describing the types of the objects.
     */
    @Throws(NotSerializableException::class)
    fun <T : Any> deserialize(bytes: ByteSequence, clazz: Class<T>, context: SerializationContext): T =
            des {
                val envelope = getEnvelope(bytes, encodingWhitelist)
                clazz.cast(readObjectOrNull(envelope.obj, SerializationSchemas(envelope.schema, envelope.transformsSchema),
                        clazz, context))
            }

    @Throws(NotSerializableException::class)
    fun <T : Any> deserializeAndReturnEnvelope(
            bytes: SerializedBytes<T>,
            clazz: Class<T>,
            context: SerializationContext
    ): ObjectAndEnvelope<T> = des {
        val envelope = getEnvelope(bytes, encodingWhitelist)
        // Now pick out the obj and schema from the envelope.
        ObjectAndEnvelope(
                clazz.cast(readObjectOrNull(
                        envelope.obj,
                        SerializationSchemas(envelope.schema, envelope.transformsSchema),
                        clazz,
                        context)),
                envelope)
    }

    internal fun readObjectOrNull(obj: Any?, schema: SerializationSchemas, type: Type, context: SerializationContext,
                                  offset: Int = 0
    ): Any? {
        return if (obj == null) null else readObject(obj, schema, type, context, offset)
    }

    internal fun readObject(obj: Any, schemas: SerializationSchemas, type: Type, context: SerializationContext, debugIndent: Int = 0): Any =
            if (obj is DescribedType && ReferencedObject.DESCRIPTOR == obj.descriptor) {
                // It must be a reference to an instance that has already been read, cheaply and quickly returning it by reference.
                val objectIndex = (obj.described as UnsignedInteger).toInt()
                if (objectIndex !in 0..objectHistory.size)
                    throw NotSerializableException("Retrieval of existing reference failed. Requested index $objectIndex " +
                            "is outside of the bounds for the list of size: ${objectHistory.size}")

                val objectRetrieved = objectHistory[objectIndex]
                if (!objectRetrieved::class.java.isSubClassOf(type.asClass()!!)) {
                    throw NotSerializableException(
                            "Existing reference type mismatch. Expected: '$type', found: '${objectRetrieved::class.java}' " +
                                    "@ ${objectIndex}")
                }
                objectRetrieved
            } else {
                val objectRead = when (obj) {
                    is DescribedType -> {
                        // Look up serializer in factory by descriptor
                        val serializer = serializerFactory.get(obj.descriptor, schemas)
                        if (SerializerFactory.AnyType != type && serializer.type != type && with(serializer.type) {
                                    !isSubClassOf(type) && !materiallyEquivalentTo(type)
                                }) {
                            throw NotSerializableException("Described type with descriptor ${obj.descriptor} was " +
                                    "expected to be of type $type but was ${serializer.type}")
                        }
                        serializer.readObject(obj.described, schemas, this, context)
                    }
                    is Binary -> obj.array
                    else -> obj // this will be the case for primitive types like [boolean] et al.
                }

                // Store the reference in case we need it later on.
                // Skip for primitive types as they are too small and overhead of referencing them will be much higher
                // than their content
                if (suitableForObjectReference(objectRead.javaClass)) {
                    objectHistory.add(objectRead)
                }
                objectRead
            }

    /**
     * Currently performs checks aimed at:
     *  * [java.util.List<Command<?>>] and [java.lang.Class<? extends net.corda.core.contracts.Contract>]
     *  * [T : Parent] and [Parent]
     *  * [? extends Parent] and [Parent]
     *
     * In the future tighter control might be needed
     */
    private fun Type.materiallyEquivalentTo(that: Type): Boolean =
            when (that) {
                is ParameterizedType -> asClass() == that.asClass()
                is TypeVariable<*> -> isSubClassOf(that.bounds.first())
                is WildcardType -> isSubClassOf(that.upperBounds.first())
                else -> false
            }
}

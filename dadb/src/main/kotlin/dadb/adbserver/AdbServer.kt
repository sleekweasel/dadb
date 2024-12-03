package dadb.adbserver

import dadb.AdbStream
import dadb.Dadb
import okio.buffer
import okio.sink
import okio.source
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets


object AdbServer {

    /**
     * Experimental API
     *
     * Possible deviceQuery values:
     *
     * host-serial:<serial-id>
     *     This is a special form of query, where the 'host-serial::'
     *     prefix can be used to indicate that the client is asking the ADB server
     *     for information related to a specific device. can include a :port suffix
     *     for 'adb connect' devices.
     *     This survives devices being reconnected.
     *
     * host:transport:<transport-id>
     *     Ask to switch the connection to the device/emulator identified by
     *     <transport-id> in 'adb -devices -l'. After the OKAY response, every
     *     client request will be sent directly to the adbd daemon running on the
     *     device.
     *     Risky: if the device is briefly unplugged, its transport_id will change.
     *     (Used to implement the -s option)
     *
     * host:transport-usb
     *     Ask to switch the connection to one device connected through USB
     *     to the host machine. This will fail if there are more than one such
     *     devices. (Used to implement the -d convenience option)
     *
     * host:transport-local
     *     Ask to switch the connection to one emulator connected through TCP.
     *     This will fail if there is more than one such emulator instance
     *     running. (Used to implement the -e convenience option)
     *
     * host:transport-any
     *     Another host:transport variant. Ask to switch the connection to
     *     either the device or emulator connect to/running on the host.
     *     Will fail if there is more than one such device/emulator available.
     *     (Used when neither -s, -d or -e are provided)
     */
    @JvmStatic
    @JvmOverloads
    fun createDadb(
        adbServerHost: String = "localhost",
        adbServerPort: Int = 5037,
        deviceQuery: String = "host:transport-any",
        connectTimeout: Int = 0,
        socketTimeout: Int = 0,
        serial: String? = null,
    ): Dadb {
        val name = serial ?: deviceQuery
            .removePrefix("host:") // Use the device query without the host: prefix
            .removePrefix("host-serial:") // Present the serial
            .removePrefix("transport:") // If it's a transport-id, just show that
        return AdbServerDadb(adbServerHost, adbServerPort, deviceQuery, name, connectTimeout, socketTimeout)
    }

    /**
     * Returns a list of serial numbers of connected devices.
     */
    @JvmStatic
    @JvmOverloads
    fun listDadbs(
        adbServerHost: String = "localhost",
        adbServerPort: Int = 5037,
    ): List<Dadb> {
        if (!AdbBinary.tryStartServer(adbServerHost, adbServerPort)) {
            return emptyList()
        }
        val output = Socket(adbServerHost, adbServerPort).use { socket ->
            send(socket, "host:devices-l") // devices-l for transport-id:
            readString(DataInputStream(socket.getInputStream()))
        }
        return output.lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                // 59652cce               device usb:34603008X product:NE2213EEA model:NE2213 device:OP516FL1 transport_id:5
                val parts = line.split(Regex("\\s+"))
                val transportId = parts
                    .firstOrNull { it.startsWith("transport_id:") }
                    ?.removePrefix("transport_id:")
                    ?.toInt()
                if ( transportId == null ) null else parts[0] to transportId
            }
            .map { createDadb(adbServerHost, adbServerPort, "host:transport-id:${it.second}", serial = it.first) }
//            .map { createDadb(adbServerHost, adbServerPort, "host:transport:${it}") }
//            .map { createDadb(adbServerHost, adbServerPort, "host-serial:${it}") }
    }

    internal fun readString(inputStream: DataInputStream): String {
        val encodedLength = readString(inputStream, 4)
        val length = encodedLength.toInt(16)
        return readString(inputStream, length)
    }

    internal fun send(socket: Socket, command: String) {
        val inputStream = DataInputStream(socket.getInputStream())
        val outputStream = DataOutputStream(socket.getOutputStream())

        writeString(outputStream, command)

        val response = readString(inputStream, 4)
        if (response != "OKAY") {
            val error = readString(inputStream)
            throw IOException("Command failed ($command): $error")
        }
    }

    private fun writeString(outputStream: DataOutputStream, string: String) {
        OutputStreamWriter(outputStream, StandardCharsets.UTF_8).apply {
            write(String.format("%04x", string.toByteArray().size))
            write(string)
            flush()
        }
    }

    private fun readString(inputStream: DataInputStream, length: Int): String {
        val responseBuffer = ByteArray(length)
        inputStream.readFully(responseBuffer)
        return String(responseBuffer, StandardCharsets.UTF_8)
    }
}

private class AdbServerDadb constructor(
    private val host: String,
    private val port: Int,
    private val deviceQuery: String,
    private val name: String,
    private val connectTimeout: Int = 0,
    private val socketTimeout: Int = 0,
) : Dadb {

    private val supportedFeatures: Set<String>

    init {
        supportedFeatures = open("${deviceQuery.replace("host:transport", "host-transport")}:features").use {
//         supportedFeatures = open("host-serial:$name:features").use { // Fixes more than one device/emulator
            val features = AdbServer.readString(DataInputStream(it.source.inputStream()))
            features.split(",").toSet()
        }
    }

    override fun open(destination: String): AdbStream {
        AdbBinary.ensureServerRunning(host, port)

        val socketAddress = InetSocketAddress(host, port)
        val socket = Socket()
        socket.soTimeout = socketTimeout
        socket.connect(socketAddress, connectTimeout)

        AdbServer.send(socket, deviceQuery)
        AdbServer.send(socket, destination)
        return object : AdbStream {

            override val source = socket.source().buffer()

            override val sink = socket.sink().buffer()

            override fun close() = socket.close()
        }
    }

    override fun supportsFeature(feature: String): Boolean {
        return feature in supportedFeatures
    }

    override fun close() {}

    override fun toString(): String {
        return name
    }
}

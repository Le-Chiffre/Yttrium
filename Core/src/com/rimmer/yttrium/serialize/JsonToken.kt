package com.rimmer.yttrium.serialize

import com.rimmer.yttrium.InvalidStateException
import io.netty.buffer.ByteBuf

/** Represents a json token with parsing functionality. */
class JsonToken(val buffer: ByteBuf) {
    enum class Type {
        StartObject,
        EndObject,
        StartArray,
        EndArray,
        StringLit,
        NumberLit,
        BoolLit,
        NullLit,
        FieldName
    }

    var type = Type.StartObject
    var boolPayload = false
    var numberPayload = 0.0
    var stringPayload = ""

    fun expect(type: Type, allowNull: Boolean = false) {
        parse()
        if(this.type != type || (this.type == Type.NullLit && !allowNull)) {
            throw InvalidStateException("Invalid json: Expected $type")
        }
    }

    fun parse() {
        skipWhitespace()
        val b = buffer.readByte().toInt()
        if(b == '['.toInt()) {
            type = Type.StartArray
        } else if(b == ']'.toInt()) {
            type = Type.EndArray
        } else if(b == '{'.toInt()) {
            type = Type.StartObject
        } else if(b == '}'.toInt()) {
            type = Type.EndObject
        } else {
            parseValue(b.toChar())
        }
    }

    fun peekArrayEnd(): Boolean {
        return buffer.getByte(buffer.readerIndex()).toChar() == ']'
    }

    private fun parseValue(first: Char) {
        if(first == '"'.toChar()) {
            stringPayload = parseString()
            val f = buffer.getByte(buffer.readerIndex()).toChar()
            if(f == ':') {
                type = Type.FieldName
                buffer.skipBytes(1)
            } else {
                type = Type.StringLit
            }
        } else if(isDigit(first)) {
            type = Type.NumberLit
            numberPayload = parseFloat(first)
        } else if(first == 't') {
            expectChar('r')
            expectChar('u')
            expectChar('e')
            type = Type.BoolLit
            boolPayload = true
        } else if(first == 'f') {
            expectChar('a')
            expectChar('l')
            expectChar('s')
            expectChar('e')
            type = Type.BoolLit
            boolPayload = false
        } else if(first == 'n') {
            expectChar('u')
            expectChar('l')
            expectChar('l')
            type = Type.NullLit
        } else {
            throw InvalidStateException("Invalid json: expected a value")
        }

        val c = buffer.getByte(buffer.readerIndex()).toChar()
        if(c == ',') {
            buffer.skipBytes(1)
        }
    }

    private fun parseFloat(first: Char): Double {
        var ch = first
        var out = 0.0

        // Check sign.
        var neg = false
        if(ch == '+') {
            ch = buffer.readByte().toChar()
        } else if(ch == '-') {
            ch = buffer.readByte().toChar()
            neg = true
        }

        // Create part before decimal point.
        while(isDigit(ch)) {
            val n = Character.digit(ch, 10)
            out *= 10.0
            out += n
            ch = buffer.readByte().toChar()
        }

        // Check if there is a fractional part.
        if(ch == '.') {
            ch = buffer.readByte().toChar()
            var dec = 0.0
            var dpl = 0

            while(isDigit(ch)) {
                val n = Character.digit(ch, 10)
                dec *= 10.0
                dec += n

                dpl++
                ch = buffer.readByte().toChar()
            }

            // We need to use a floating point power here in order to support more than 9 decimals.
            val power = Math.pow(10.0, dpl.toDouble())
            dec /= power
            out += dec
        }

        // Check if there is an exponent.
        if(ch == 'E' || ch == 'e') {
            ch = buffer.readByte().toChar()

            // Check sign.
            var signNegative = false
            if(ch == '+') {
                ch = buffer.readByte().toChar()
            } else if(ch == '-') {
                ch = buffer.readByte().toChar()
                signNegative = true
            }

            // Has exp. part;
            var exp = 0.0

            while(Character.isDigit(ch)) {
                val n = Character.digit(ch, 10)
                exp *= 10.0
                exp += n
                ch = buffer.readByte().toChar()
            }

            if(signNegative) exp = -exp;

            val power = Math.pow(10.0, exp)
            out *= power
        }

        if(neg) out = -out

        return out
    }

    private fun parseString(): String {
        val string = StringBuilder()
        while(true) {
            val b = buffer.readByte().toInt()
            if(b == '"'.toInt()) {
                break
            } else if(b == '\\'.toInt()) {
                parseEscaped(string)
            } else {
                string.append(b.toChar())
            }
        }
        return string.toString()
    }

    private fun parseEscaped(string: StringBuilder) {
        val b = buffer.readByte().toInt()
        if(b == '"'.toInt()) {
            string.append('"')
        } else if(b == '\\'.toInt()) {
            string.append('\\')
        } else if(b == '/'.toInt()) {
            string.append('/')
        } else if(b == 'b'.toInt()) {
            string.append('\b')
        } else if(b == 'f'.toInt()) {
            string.append(0x0C.toChar())
        } else if(b == 'n'.toInt()) {
            string.append('\n')
        } else if(b == 'r'.toInt()) {
            string.append('\r')
        } else if(b == 't'.toInt()) {
            string.append('\t')
        } else if(b == 'u'.toInt()) {
            parseUnicode(string)
        }
    }

    private fun parseUnicode(string: StringBuilder) {
        val b0 = parseHexit(buffer.readByte().toChar())
        val b1 = parseHexit(buffer.readByte().toChar())
        val b2 = parseHexit(buffer.readByte().toChar())
        val b3 = parseHexit(buffer.readByte().toChar())
        val char = (b0 shl 12) or (b1 shl 8) or (b2 shl 4) or b3
        string.append(char.toChar())
    }

    private fun skipWhitespace() {
        while(true) {
            val b = buffer.getByte(buffer.readerIndex()).toInt()
            if(b == 0x20 || b == 0x09 || b == 0x0A || b == 0x0D) {
                buffer.skipBytes(1)
            } else {
                break
            }
        }
    }

    private fun expectChar(c: Char) {
        val ch = buffer.readByte().toChar()
        if(ch != c) {
            throw InvalidStateException("Invalid json: expected '$c'")
        }
    }
}

/**
 * Parses the provided character as a hexit, to an integer in the range 0..15.
 * @return The parsed number. Returns Nothing if the character is not a valid number.
 */
fun parseHexit(c: Char): Int {
    val ch = c
    val index = ch - '0'

    if(index < 0 || index > 54) {
        throw InvalidStateException("Invalid json: expected unicode sequence")
    }

    val res = parseHexitLookup[index]
    if(res > 15) {
        throw InvalidStateException("Invalid json: expected unicode sequence")
    }

    return res
}

// We use a small lookup table for parseHexit,
// since the number of branches would be ridiculous otherwise.
val parseHexitLookup = arrayOf(
    0,  1,  2,  3,  4,  5,  6,  7,  8,  9,	/* 0..9 */
    255,255,255,255,255,255,255,			/* :..@ */
    10, 11, 12, 13, 14, 15,					/* A..F */
    255,255,255,255,255,255,255,			/* G..` */
    255,255,255,255,255,255,255,
    255,255,255,255,255,255,255,
    255,255,255,255,255,255,
    10, 11, 12, 13, 14, 15					/* a..f */
)

/**
 * Returns true if this is a digit.
 */
fun isDigit(c: Char): Boolean {
    val ch = c
    val index = ch - '0'
    return index <= 9 && index >= 0
}

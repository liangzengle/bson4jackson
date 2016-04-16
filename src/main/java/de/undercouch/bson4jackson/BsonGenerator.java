// Copyright 2010-2011 Michel Kraemer
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package de.undercouch.bson4jackson;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.util.Date;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.Base64Variant;
import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.core.base.GeneratorBase;
import com.fasterxml.jackson.core.io.CharacterEscapes;
import com.fasterxml.jackson.core.json.JsonWriteContext;
import com.fasterxml.jackson.databind.SerializerProvider;

import de.undercouch.bson4jackson.io.ByteOrderUtil;
import de.undercouch.bson4jackson.io.DynamicOutputBuffer;
import de.undercouch.bson4jackson.types.JavaScript;
import de.undercouch.bson4jackson.types.ObjectId;
import de.undercouch.bson4jackson.types.Symbol;
import de.undercouch.bson4jackson.types.Timestamp;

/**
 * Writes BSON code to the provided output stream
 * @author Michel Kraemer
 */
public class BsonGenerator extends GeneratorBase {
	/**
     * Defines toggable features
     */
	public enum Feature {
		/**
		 * <p>Enables streaming by setting the document's total
		 * number of bytes in the header to 0. This allows the generator
		 * to flush the output buffer from time to time. Otherwise the
		 * generator would have to buffer the whole file to be able to
		 * calculate the total number of bytes.</p>
		 * <p><b>ATTENTION:</b> By enabling this feature, the BSON document
		 * generated by this class will not be compatible to the
		 * specification! However, if you know what you are doing and
		 * if you know that the document will be read by a parser that
		 * ignores the total number of bytes anyway (like {@link BsonParser}
		 * or <code>org.bson.BSONDecoder</code> from the MongoDB Java Driver
		 * do) then this feature will be very useful.</p>
		 * <p>This feature is disabled by default.</p>
		 */
		ENABLE_STREAMING,
		
		/**
		 * <p>Forces {@link BigDecimal}s to be written as {@link String}s.
		 * The BSON format supports IEEE 754 doubles only (64 bits). You
		 * may want to enable this feature if you want to serialize numbers
		 * that require more bits or a higher accuracy.</p>
		 * <p>This feature is disabled by default.</p>
		 */
		WRITE_BIGDECIMALS_AS_STRINGS;
		
		/**
		 * @return the bit mask that identifies this feature
		 */
		public int getMask() {
			return 1 << ordinal();
		}
	}
	
	/**
	 * A structure describing the document currently being generated
	 * @author Michel Kraemer
	 */
	protected static class DocumentInfo {
		/**
		 * Information about the parent document (may be null if this
		 * document is the top-level one)
		 */
		final DocumentInfo parent;
		
		/**
		 * The position of the document's header in the output buffer
		 */
		final int headerPos;
		
		/**
		 * The current position in the array or -1 if the
		 * document is no array
		 */
		int currentArrayPos;
		
		/**
		 * Creates a new DocumentInfo object
		 * @param parent information about the parent document (may be
		 * null if this document is the top-level one)
		 * @param headerPos the position of the document's header
		 * in the output buffer
		 * @param array true if the document is an array
		 */
		public DocumentInfo(DocumentInfo parent, int headerPos, boolean array) {
			this.parent = parent;
			this.headerPos = headerPos;
			this.currentArrayPos = array ? 0 : -1;
		}
	}
	
	/**
	 * Bit flag composed of bits that indicate which
	 * {@link Feature}s are enabled.
	 */
    protected final int _bsonFeatures;
    
	/**
	 * The output stream to write to
	 */
	protected final OutputStream _out;
	
	/**
	 * Since a BSON document's header must include the size of the whole document
	 * in bytes, we have to buffer the whole document first, before we can
	 * write it to the output stream. BSON specifies LITTLE_ENDIAN for all tokens.
	 */
	protected final DynamicOutputBuffer _buffer = new DynamicOutputBuffer(ByteOrder.LITTLE_ENDIAN);
	
	/**
	 * Saves the position of the type marker for the object currently begin written
	 */
	protected int _typeMarker = 0;
	
	/**
	 * Saves information about documents (the main document and embedded ones)
	 */
	protected DocumentInfo _currentDocument;

	/**
	 * Indicates that the next object to be encountered is actually embedded inside a value, and not a complete value
	 * itself.  This causes things like context validation and writing out the type to be skipped.
	 */
	protected boolean nextObjectIsEmbeddedInValue = false;

	/**
	 * Custom character escapes to use when writing strings (field names and string values)
	 */
	protected CharacterEscapes _characterEscapes = null;
	
	/**
	 * Escape codes to use when writing strings (only valid of {@link #_characterEscapes} is not null,
	 * see {@link #setCharacterEscapes(CharacterEscapes)})
	 */
	protected int[] _outputEscapes = null;

	/**
	 * Creates a new generator
	 * @param jsonFeatures bit flag composed of bits that indicate which
     * {@link com.fasterxml.jackson.core.JsonGenerator.Feature}s are enabled.
     * @param bsonFeatures bit flag composed of bits that indicate which
	 * {@link Feature}s are enabled.
	 * @param out the output stream to write to
	 */
	public BsonGenerator(int jsonFeatures, int bsonFeatures, OutputStream out) {
		super(jsonFeatures, null);
		_bsonFeatures = bsonFeatures;
		_out = out;
		
		if (isEnabled(Feature.ENABLE_STREAMING)) {
			//if streaming is enabled, try to reuse some buffers
			//this will save garbage collector cycles if the tokens
			//written to the buffer are not too large
			_buffer.setReuseBuffersCount(2);
		}
	}
	
	@Override
	public JsonGenerator setCharacterEscapes(CharacterEscapes esc) {
		_characterEscapes = esc;
		if (esc == null) {
			_outputEscapes = null;
		} else {
			_outputEscapes = esc.getEscapeCodesForAscii();
		}
		return this;
	}

	@Override
	public CharacterEscapes getCharacterEscapes() {
		return _characterEscapes;
	}
	
	/**
	 * Checks if a generator feature is enabled
	 * @param f the feature
	 * @return true if the given feature is enabled
	 */
	protected boolean isEnabled(Feature f) {
		return (_bsonFeatures & f.getMask()) != 0;
	}
	
	/**
	 * @return true if the generator is currently processing an array
	 */
	protected boolean isArray() {
		return _currentDocument == null ? false : _currentDocument.currentArrayPos >= 0;
	}
	
	/**
	 * Retrieves and then increases the current position in the array
	 * currently being generated
	 * @return the position (before it has been increased) or -1 if
	 * the current document is not an array
	 */
	protected int getAndIncCurrentArrayPos() {
		if (_currentDocument == null) {
			return -1;
		}
		int r = _currentDocument.currentArrayPos;
		++_currentDocument.currentArrayPos;
		return r;
	}
	
	/**
	 * Reserves bytes for the BSON document header
	 */
	protected void reserveHeader() {
		_buffer.putInt(0);
	}
	
	/**
	 * Writes the BSON document header to the output buffer at the
	 * given position. Does not increase the buffer's write position. 
	 * @param pos the position where to write the header
	 */
	protected void putHeader(int pos) {
		_buffer.putInt(pos, _buffer.size() - pos);
	}
	
	@Override
	public void flush() throws IOException {
		_buffer.writeTo(_out);
		_buffer.clear();
		_out.flush();
	}

	@Override
	protected void _releaseBuffers() {
		_buffer.clear();
	}
	
	@Override
	public void close() throws IOException {
		//finish document
		if (isEnabled(JsonGenerator.Feature.AUTO_CLOSE_JSON_CONTENT)) {
			while (_currentDocument != null) {
				writeEndObject();
			}
		}
		
		//write buffer to output stream (if streaming is enabled,
		//this will write the the rest of the buffer)
		_buffer.writeTo(_out);
		_buffer.clear();
		_out.flush();
		
		if (isEnabled(JsonGenerator.Feature.AUTO_CLOSE_TARGET)) {
			_out.close();
		}
		
		super.close();
	}
	
	@Override
	public void writeStartArray() throws IOException,
			JsonGenerationException {
		_verifyValueWrite("start an array");
        _writeContext = _writeContext.createChildArrayContext();
		_writeStartObject(true);
	}

	@Override
	public void writeEndArray() throws IOException, JsonGenerationException {
		if (!_writeContext.inArray()) {
            _reportError("Current context not an ARRAY but " + _writeContext.getTypeDesc());
        }
		writeEndObjectInternal();
		_writeContext = _writeContext.getParent();
	}

	@Override
	public void writeStartObject() throws IOException, JsonGenerationException {
		if (nextObjectIsEmbeddedInValue) {
			_writeContext = _writeContext.createChildObjectContext();
			_currentDocument = new DocumentInfo(_currentDocument, _buffer.size(), false);
			reserveHeader();

			// We've skipped everything we need to skip, the next object may not be embedded in a value
			nextObjectIsEmbeddedInValue = false;
		} else {
			_verifyValueWrite("start an object");
			_writeContext = _writeContext.createChildObjectContext();
			_writeStartObject(false);
		}
	}
	
	/**
	 * Creates a new embedded document or array
	 * @param array true if the embedded object is an array
	 * @throws IOException if the document could not be created
	 */
	protected void _writeStartObject(boolean array) throws IOException {
		_writeArrayFieldNameIfNeeded();
		if (_currentDocument != null) {
			//embedded document/array
			_buffer.putByte(_typeMarker, array ? BsonConstants.TYPE_ARRAY :
				BsonConstants.TYPE_DOCUMENT);
		}
		_currentDocument = new DocumentInfo(_currentDocument, _buffer.size(), array);
		reserveHeader();
	}

	@Override
	public void writeEndObject() throws IOException, JsonGenerationException {
		if (!_writeContext.inObject()) {
            _reportError("Current context not an object but " +
            		_writeContext.getTypeDesc());
        }
        _writeContext = _writeContext.getParent();
        writeEndObjectInternal();
	}
        
	protected void writeEndObjectInternal() {
		if (_currentDocument != null) {
			_buffer.putByte(BsonConstants.TYPE_END);
			DocumentInfo info = _currentDocument;
			_currentDocument = _currentDocument.parent;
			
			//re-write header to update document size (only if
			//streaming is not enabled since in this case the buffer
			//containing the header might not be available anymore)
			if (!isEnabled(Feature.ENABLE_STREAMING)) {
				putHeader(info.headerPos);
			}
		}
	}
	
	/**
	 * If the generator is currently processing an array, this method writes
	 * the field name of the current element (which is just the position of the
	 * element in the array)
	 * @throws IOException if the field name could not be written
	 */
	protected void _writeArrayFieldNameIfNeeded() throws IOException {
		if (isArray()) {
			int p = getAndIncCurrentArrayPos();
			_writeFieldName(String.valueOf(p));
		}
	}

	@Override
	public void writeFieldName(String name) throws IOException, JsonGenerationException {
		int status = _writeContext.writeFieldName(name);
        if (status == JsonWriteContext.STATUS_EXPECT_VALUE) {
            _reportError("Can not write a field name, expecting a value");
        }
        _writeFieldName(name);
	}
        
	protected void _writeFieldName(String name) throws IOException, JsonGenerationException {
		//escape characters if necessary
		name = escapeCharacters(name);
		
		//reserve bytes for the type
		_typeMarker = _buffer.size();
		_buffer.putByte((byte)0);
		
		//write field name
		_buffer.putUTF8(name);
		_buffer.putByte(BsonConstants.END_OF_STRING);
	}

	@Override
	protected void _verifyValueWrite(String typeMsg) throws IOException {
		int status = _writeContext.writeValue();
		if (status == JsonWriteContext.STATUS_EXPECT_NAME) {
			_reportError("Can not " + typeMsg + ", expecting field name");
		}
	}
	
	/**
	 * Tries to flush the output buffer if streaming is enabled. This
	 * method is a no-op if streaming is disabled.
	 * @throws IOException if flushing failed
	 */
	protected void flushBuffer() throws IOException {
		if (isEnabled(Feature.ENABLE_STREAMING)) {
			_buffer.flushTo(_out);
		}
	}

	@Override
	public void writeString(String text) throws IOException,
			JsonGenerationException {
		_writeArrayFieldNameIfNeeded();
		
		_verifyValueWrite("write string");
		_buffer.putByte(_typeMarker, BsonConstants.TYPE_STRING);

		_writeString(text);

		flushBuffer();
	}

	@Override
	public void writeString(char[] text, int offset, int len)
			throws IOException, JsonGenerationException {
		writeString(new String(text, offset, len));
	}

	@Override
	public void writeRaw(String text) throws IOException,
			JsonGenerationException {
		_writeArrayFieldNameIfNeeded();
		_verifyValueWrite("write raw string");
		_buffer.putByte(_typeMarker, BsonConstants.TYPE_BINARY);
		_buffer.putInt(text.length() * 2);
		_buffer.putByte(BsonConstants.SUBTYPE_BINARY);
		_buffer.putString(text);
		flushBuffer();
	}

	@Override
	public void writeRaw(String text, int offset, int len) throws IOException,
			JsonGenerationException {
		writeRaw(text.substring(offset, len));
	}

	@Override
	public void writeRaw(char[] text, int offset, int len) throws IOException,
			JsonGenerationException {
		_writeArrayFieldNameIfNeeded();
		_verifyValueWrite("write raw string");
		_buffer.putByte(_typeMarker, BsonConstants.TYPE_BINARY);
		_buffer.putInt(text.length * 2);
		_buffer.putByte(BsonConstants.SUBTYPE_BINARY);
		_buffer.putString(CharBuffer.wrap(text));
		flushBuffer();
	}

	@Override
	public void writeRaw(char c) throws IOException, JsonGenerationException {
		writeRaw(new char[] { c }, 0, 1);
	}

	@Override
	public void writeBinary(Base64Variant b64variant, byte[] data, int offset,
			int len) throws IOException, JsonGenerationException {
		writeBinary(b64variant, BsonConstants.SUBTYPE_BINARY, data,
				offset, len);
	}

	/**
	 * Similar to {@link #writeBinary(Base64Variant, byte, byte[], int, int)},
	 * but with the possibility to specify a binary subtype (see
	 * {@link BsonConstants}).
	 * @param b64variant base64 variant to use (will be ignored for BSON)
	 * @param subType the binary subtype
	 * @param data the binary data to write
	 * @param offset the offset of the first byte to write
	 * @param len the number of bytes to write
	 * @throws IOException if the binary data could not be written
	 */
	public void writeBinary(Base64Variant b64variant, byte subType,
			byte[] data, int offset, int len) throws IOException {
		//base64 is not needed for BSON
		_writeArrayFieldNameIfNeeded();
		_verifyValueWrite("write binary");
		_buffer.putByte(_typeMarker, BsonConstants.TYPE_BINARY);
		_buffer.putInt(len);
		_buffer.putByte(subType);
		int end = offset + len;
		if (end > data.length) {
			end = data.length;
		}
		while (offset < end) {
			_buffer.putByte(data[offset]);
			++offset;
		}
		flushBuffer();
	}

	@Override
	public void writeNumber(int v) throws IOException, JsonGenerationException {
		_writeArrayFieldNameIfNeeded();
		_verifyValueWrite("write number");
		_buffer.putByte(_typeMarker, BsonConstants.TYPE_INT32);
		_buffer.putInt(v);
		flushBuffer();
	}

	@Override
	public void writeNumber(long v) throws IOException, JsonGenerationException {
		_writeArrayFieldNameIfNeeded();
		_verifyValueWrite("write number");
		_buffer.putByte(_typeMarker, BsonConstants.TYPE_INT64);
		_buffer.putLong(v);
		flushBuffer();
	}

	@Override
	public void writeNumber(BigInteger v) throws IOException,
			JsonGenerationException {
		int bl = v.bitLength();
		if (bl < 32) {
			writeNumber(v.intValue());
		} else if (bl < 64) {
			writeNumber(v.longValue());
		} else {
			writeString(v.toString());
		}
	}

	@Override
	public void writeNumber(double d) throws IOException,
			JsonGenerationException {
		_writeArrayFieldNameIfNeeded();
		_verifyValueWrite("write number");
		_buffer.putByte(_typeMarker, BsonConstants.TYPE_DOUBLE);
		_buffer.putDouble(d);
		flushBuffer();
	}

	@Override
	public void writeNumber(float f) throws IOException,
			JsonGenerationException {
		//BSON understands double values only
		writeNumber((double)f);
	}

	@Override
	public void writeNumber(BigDecimal dec) throws IOException,
			JsonGenerationException {
		if (isEnabled(Feature.WRITE_BIGDECIMALS_AS_STRINGS)) {
			writeString(dec.toString());
			return;
		}
		
		float f = dec.floatValue();
		if (!Float.isInfinite(f)) {
			writeNumber(f);
		} else {
			double d = dec.doubleValue();
			if (!Double.isInfinite(d)) {
				writeNumber(d);
			} else {
				writeString(dec.toString());
			}
		}
	}

	@Override
	public void writeNumber(String encodedValue) throws IOException,
			JsonGenerationException, UnsupportedOperationException {
		writeString(encodedValue);
	}

	@Override
	public void writeBoolean(boolean state) throws IOException,
			JsonGenerationException {
		_writeArrayFieldNameIfNeeded();
		_verifyValueWrite("write boolean");
		_buffer.putByte(_typeMarker, BsonConstants.TYPE_BOOLEAN);
		_buffer.putByte((byte)(state ? 1 : 0));
		flushBuffer();
	}

	@Override
	public void writeNull() throws IOException, JsonGenerationException {
		_writeArrayFieldNameIfNeeded();
		_verifyValueWrite("write null");
		_buffer.putByte(_typeMarker, BsonConstants.TYPE_NULL);
		flushBuffer();
	}

	@Override
	public void writeRawUTF8String(byte[] text, int offset, int length)
			throws IOException, JsonGenerationException {
		_writeArrayFieldNameIfNeeded();
		
		_verifyValueWrite("write raw utf8 string");
		_buffer.putByte(_typeMarker, BsonConstants.TYPE_STRING);
		
		//reserve space for the string size
		int p = _buffer.size();
		_buffer.putInt(0);
		
		//write string
		for (int i = offset; i < length; ++i) {
			_buffer.putByte(text[i]);
		}
		_buffer.putByte(BsonConstants.END_OF_STRING);
		
		//write string size
		_buffer.putInt(p, length);
		
		flushBuffer();		
	}

	@Override
	public void writeUTF8String(byte[] text, int offset, int length)
			throws IOException, JsonGenerationException {
		writeRawUTF8String(text, offset, length);
	}

	/**
	 * Write a BSON date time
	 *
	 * @param date The date to write
	 * @throws IOException If an error occurred in the stream while writing
	 */
	public void writeDateTime(Date date) throws IOException {
		_writeArrayFieldNameIfNeeded();
		_verifyValueWrite("write datetime");
		_buffer.putByte(_typeMarker, BsonConstants.TYPE_DATETIME);
		_buffer.putLong(date.getTime());
		flushBuffer();
	}

	/**
	 * Write a BSON ObjectId
	 *
	 * @param objectId The objectId to write
	 * @throws IOException If an error occurred in the stream while writing
	 */
	public void writeObjectId(ObjectId objectId) throws IOException {
		_writeArrayFieldNameIfNeeded();
		_verifyValueWrite("write datetime");
		_buffer.putByte(_typeMarker, BsonConstants.TYPE_OBJECTID);
		// ObjectIds have their byte order flipped
		int time = ByteOrderUtil.flip(objectId.getTime());
		int machine = ByteOrderUtil.flip(objectId.getMachine());
		int inc = ByteOrderUtil.flip(objectId.getInc());
		_buffer.putInt(time);
		_buffer.putInt(machine);
		_buffer.putInt(inc);
		flushBuffer();
	}

	/**
	 * Converts a a Java flags word into a BSON options pattern
	 *
	 * @param flags the Java flags
	 * @return the regex options string
	 */
	protected String flagsToRegexOptions(int flags) {
		StringBuilder options = new StringBuilder();
		if ((flags & Pattern.CASE_INSENSITIVE) != 0) {
			options.append("i");
		}
		if ((flags & Pattern.MULTILINE) != 0) {
			options.append("m");
		}
		if ((flags & Pattern.DOTALL) != 0) {
			options.append("s");
		}
		if ((flags & Pattern.UNICODE_CASE) != 0) {
			options.append("u");
		}
		return options.toString();
	}

	/**
	 * Write a BSON regex
	 *
	 * @param pattern The regex to write
	 * @throws IOException If an error occurred in the stream while writing
	 */
	public void writeRegex(Pattern pattern) throws IOException {
		_writeArrayFieldNameIfNeeded();
		_verifyValueWrite("write regex");
		_buffer.putByte(_typeMarker, BsonConstants.TYPE_REGEX);
		_writeCString(pattern.pattern());
		_writeCString(flagsToRegexOptions(pattern.flags()));
		flushBuffer();
	}

	/**
	 * Write a MongoDB timestamp
	 *
	 * @param timestamp The timestamp to write
	 * @throws IOException If an error occurred in the stream while writing
	 */
	public void writeTimestamp(Timestamp timestamp) throws IOException {
		_writeArrayFieldNameIfNeeded();
		_verifyValueWrite("write timestamp");
		_buffer.putByte(_typeMarker, BsonConstants.TYPE_TIMESTAMP);
		_buffer.putInt(timestamp.getInc());
		_buffer.putInt(timestamp.getTime());
		flushBuffer();
	}

	/**
	 * Write a BSON JavaScript object
	 *
	 * @param javaScript The javaScript to write
	 * @param provider The serializer provider, for serializing the scope
	 * @throws IOException If an error occurred in the stream while writing
	 */
	public void writeJavaScript(JavaScript javaScript, SerializerProvider provider) throws IOException {
		_writeArrayFieldNameIfNeeded();
		_verifyValueWrite("write javascript");
		if (javaScript.getScope() == null) {
			_buffer.putByte(_typeMarker, BsonConstants.TYPE_JAVASCRIPT);
			_writeString(javaScript.getCode());
		} else {
			_buffer.putByte(_typeMarker, BsonConstants.TYPE_JAVASCRIPT_WITH_SCOPE);
			// reserve space for the entire structure size
			int p = _buffer.size();
			_buffer.putInt(0);

			// write the code
			_writeString(javaScript.getCode());

			nextObjectIsEmbeddedInValue = true;
			// write the document
			provider.findValueSerializer(Map.class, null).serialize(javaScript.getScope(), this, provider);
			// write the length
			if (!isEnabled(Feature.ENABLE_STREAMING)) {
				int l = _buffer.size() - p + 4;
				_buffer.putInt(p, l);
			}
		}
		flushBuffer();
	}

	/**
	 * Write a BSON Symbol object
	 *
	 * @param symbol The symbol to write
	 * @throws IOException If an error occurred in the stream while writing
	 */
	public void writeSymbol(Symbol symbol) throws IOException {
		_writeArrayFieldNameIfNeeded();
		_verifyValueWrite("write symbol");
		_buffer.putByte(_typeMarker, BsonConstants.TYPE_SYMBOL);
		_writeString(symbol.getSymbol());
		flushBuffer();
	}

	/**
	 * Write a BSON string structure (a null terminated string prependend by the length of the string)
	 *
	 * @param string The string to write
	 * @return The number of bytes written, including the terminating null byte and the size of the string
	 * @throws IOException If an error occurred in the stream while writing
	 */
	protected int _writeString(String string) throws IOException {
		//reserve space for the string size
		int p = _buffer.size();
		_buffer.putInt(0);

		//write string
		int l = _writeCString(string);

		//write string size
		_buffer.putInt(p, l);
		return l + 4;
	}

	/**
	 * Write a BSON cstring structure (a null terminated string)
	 *
	 * @param string The string to write
	 * @return The number of bytes written, including the terminating null byte
	 * @throws IOException If an error occurred in the stream while writing 
	 */
	protected int _writeCString(String string) throws IOException {
		//escape characters if necessary
		string = escapeCharacters(string);
		int l = _buffer.putUTF8(string);
		_buffer.putByte(BsonConstants.END_OF_STRING);
		return l + 1;
	}
	
	/**
	 * Escapes the given string according to {@link #_characterEscapes}. If
	 * there are no character escapes returns the original string.
	 * @param string the string to escape
	 * @return the escaped string or the original one if there is nothing to escape
	 * @throws IOException if an escape sequence could not be retrieved
	 */
	protected String escapeCharacters(String string) throws IOException {
		if (_characterEscapes == null) {
			//escaping not necessary
			return string;
		}
		
		StringBuilder sb = null;
		int lastEscapePos = 0;
		
		for (int i = 0; i < string.length(); ++i) {
			int c = string.charAt(i);
			if (c <= 0x7F && _outputEscapes[c] == CharacterEscapes.ESCAPE_CUSTOM) {
				SerializableString escape = _characterEscapes.getEscapeSequence(c);
				if (escape == null) {
					_reportError("Invalid custom escape definitions; custom escape "
							+ "not found for character code 0x" + Integer.toHexString(c) +
							", although was supposed to have one");
				}
				if (sb == null) {
					sb = new StringBuilder();
				}
				if (i > lastEscapePos) {
					sb.append(string, lastEscapePos, i);
				}
				lastEscapePos = i + 1;
				sb.append(escape.getValue());
			}
		}
		if (sb != null && lastEscapePos < string.length()) {
			sb.append(string, lastEscapePos, string.length());
		}
		
		if (sb == null) {
			return string;
		}
		return sb.toString();
	}
}

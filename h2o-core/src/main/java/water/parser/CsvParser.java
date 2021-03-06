package water.parser;

import org.apache.commons.lang.math.NumberUtils;
import water.fvec.Vec;
import water.fvec.FileVec;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;

class CsvParser extends Parser {
  private static final byte GUESS_SEP = ParseSetup.GUESS_SEP;
  private static final int NO_HEADER = ParseSetup.NO_HEADER;
  private static final int GUESS_HEADER = ParseSetup.GUESS_HEADER;
  private static final int HAS_HEADER = ParseSetup.HAS_HEADER;

  CsvParser( ParseSetup ps ) { super(ps); }

  // Parse this one Chunk (in parallel with other Chunks)
  @SuppressWarnings("fallthrough")
  @Override public ParseWriter parseChunk(int cidx, final ParseReader din, final ParseWriter dout) {
    ValueString str = new ValueString();
    byte[] bits = din.getChunkData(cidx);
    if( bits == null ) return dout;
    int offset  = din.getChunkDataStart(cidx); // General cursor into the giant array of bytes
    final byte[] bits0 = bits;  // Bits for chunk0
    boolean firstChunk = true;  // Have not rolled into the 2nd chunk
    byte[] bits1 = null;        // Bits for chunk1, loaded lazily.
    int state;
    // If handed a skipping offset, then it points just past the prior partial line.
    if( offset >= 0 ) state = WHITESPACE_BEFORE_TOKEN;
    else {
      offset = 0; // Else start skipping at the start
      // Starting state.  Are we skipping the first (partial) line, or not?  Skip
      // a header line, or a partial line if we're in the 2nd and later chunks.
      if (_setup._check_header == ParseSetup.HAS_HEADER || cidx > 0) state = SKIP_LINE;
      else state = WHITESPACE_BEFORE_TOKEN;
    }

    // For parsing ARFF
    if (_setup._parse_type == ParserType.ARFF && _setup._check_header == ParseSetup.HAS_HEADER) state = WHITESPACE_BEFORE_TOKEN;

    int quotes = 0;
    long number = 0;
    int exp = 0;
    int sgn_exp = 1;
    boolean decimal = false;
    int fractionDigits = 0;
    int tokenStart = 0; // used for numeric token to backtrace if not successful
    int colIdx = 0;
    byte c = bits[offset];
    // skip comments for the first chunk (or if not a chunk)
    if( cidx == 0 ) {
      while ( c == '#'
              || isEOL(c)
              || c == '@' /*also treat as comments leading '@' from ARFF format*/
              || c == '%' /*also treat as comments leading '%' from ARFF format*/) {
        while ((offset   < bits.length) && (bits[offset] != CHAR_CR) && (bits[offset  ] != CHAR_LF)) {
//          System.out.print(String.format("%c",bits[offset]));
          ++offset;
        }
        if    ((offset+1 < bits.length) && (bits[offset] == CHAR_CR) && (bits[offset+1] == CHAR_LF)) ++offset;
        ++offset;
//        System.out.println();
        if (offset >= bits.length)
          return dout;
        c = bits[offset];
      }
    }
    dout.newLine();

    final boolean forceable = dout instanceof FVecParseWriter && ((FVecParseWriter)dout)._ctypes != null && _setup._column_types != null;
MAIN_LOOP:
    while (true) {
      boolean forcedEnum = forceable && colIdx < _setup._column_types.length && _setup._column_types[colIdx] == Vec.T_ENUM;
      boolean forcedString = forceable && colIdx < _setup._column_types.length && _setup._column_types[colIdx] == Vec.T_STR;

      switch (state) {
        // ---------------------------------------------------------------------
        case SKIP_LINE:
          if (isEOL(c)) {
            state = EOL;
          } else {
            break;
          }
          continue MAIN_LOOP;
        // ---------------------------------------------------------------------
        case EXPECT_COND_LF:
          state = POSSIBLE_EMPTY_LINE;
          if (c == CHAR_LF)
            break;
          continue MAIN_LOOP;
        // ---------------------------------------------------------------------
        case STRING:
          if (c == quotes) {
            state = COND_QUOTE;
            break;
          }
          if (!isEOL(c) && ((quotes != 0) || (c != CHAR_SEPARATOR))) {
            str.addChar();
            break;
          }
          // fallthrough to STRING_END
        // ---------------------------------------------------------------------
        case STRING_END:
          if ((c != CHAR_SEPARATOR) && (c == CHAR_SPACE))
            break;
          // we have parsed the string enum correctly
          if((str.get_off() + str.get_length()) > str.get_buf().length){ // crossing chunk boundary
            assert str.get_buf() != bits;
            str.addBuff(bits);
          }
          if( _setup._na_strings != null
                  && _setup._na_strings.length < colIdx  // FIXME: < is suspicious PUBDEV-869
                  && _setup._na_strings[colIdx] != null
                  && str.equals(_setup._na_strings[colIdx]))
            dout.addInvalidCol(colIdx);
          else
            dout.addStrCol(colIdx, str);
          str.set(null, 0, 0);
          ++colIdx;
          state = SEPARATOR_OR_EOL;
          // fallthrough to SEPARATOR_OR_EOL
        // ---------------------------------------------------------------------
        case SEPARATOR_OR_EOL:
          if (c == CHAR_SEPARATOR) {
            state = WHITESPACE_BEFORE_TOKEN;
            break;
          }
          if (c==CHAR_SPACE)
            break;
          // fallthrough to EOL
        // ---------------------------------------------------------------------
        case EOL:
          if(quotes != 0){
            //System.err.println("Unmatched quote char " + ((char)quotes) + " " + (((str.get_length()+1) < offset && str.get_off() > 0)?new String(Arrays.copyOfRange(bits,str.get_off()-1,offset)):""));
            dout.invalidLine("Unmatched quote char " + ((char)quotes));
            colIdx = 0;
            quotes = 0;
          }else if (colIdx != 0) {
            dout.newLine();
            colIdx = 0;
          }
          state = (c == CHAR_CR) ? EXPECT_COND_LF : POSSIBLE_EMPTY_LINE;
          if( !firstChunk )
            break MAIN_LOOP; // second chunk only does the first row
          break;
        // ---------------------------------------------------------------------
        case POSSIBLE_CURRENCY:
          if (((c >= '0') && (c <= '9')) || (c == '-') || (c == CHAR_DECIMAL_SEP) || (c == '+')) {
            state = TOKEN;
          } else {
            str.set(bits, offset - 1, 0);
            str.addChar();
            if (c == quotes) {
              state = COND_QUOTE;
              break;
            }
            if ((quotes != 0) || ((!isEOL(c) && (c != CHAR_SEPARATOR)))) {
              state = STRING;
            } else {
              state = STRING_END;
            }
          }
          continue MAIN_LOOP;
        // ---------------------------------------------------------------------
        case POSSIBLE_EMPTY_LINE:
          if (isEOL(c)) {
            if (c == CHAR_CR)
              state = EXPECT_COND_LF;
            break;
          }
          state = WHITESPACE_BEFORE_TOKEN;
          // fallthrough to WHITESPACE_BEFORE_TOKEN
        // ---------------------------------------------------------------------
        case WHITESPACE_BEFORE_TOKEN:
          if (c == CHAR_SPACE || (c == CHAR_TAB && CHAR_TAB!=CHAR_SEPARATOR)) {
              break;
          } else if (c == CHAR_SEPARATOR) {
            // we have empty token, store as NaN
            dout.addInvalidCol(colIdx++);
            break;
          } else if (isEOL(c)) {
            dout.addInvalidCol(colIdx++);
            state = EOL;
            continue MAIN_LOOP;
          }
          // fallthrough to COND_QUOTED_TOKEN
        // ---------------------------------------------------------------------
        case COND_QUOTED_TOKEN:
          state = TOKEN;
          if( CHAR_SEPARATOR!=HIVE_SEP && // Only allow quoting in CSV not Hive files
              ((_setup._single_quotes && c == CHAR_SINGLE_QUOTE) || (c == CHAR_DOUBLE_QUOTE))) {
            assert (quotes == 0);
            quotes = c;
            break;
          }
          // fallthrough to TOKEN
        // ---------------------------------------------------------------------
        case TOKEN:
          if( dout.isString(colIdx) ) { // Forced already to a string col?
            state = STRING; // Do not attempt a number parse, just do a string parse
            str.set(bits, offset, 0);
            continue MAIN_LOOP;
          } else if (((c >= '0') && (c <= '9')) || (c == '-') || (c == CHAR_DECIMAL_SEP) || (c == '+')) {
            state = NUMBER;
            number = 0;
            fractionDigits = 0;
            decimal = false;
            tokenStart = offset;
            if (c == '-') {
              exp = -1;
              break;
            } else if(c == '+'){
              exp = 1;
              break;
            } else {
              exp = 1;
            }
            // fallthrough
          } else if (c == '$') {
            state = POSSIBLE_CURRENCY;
            break;
          } else {
            state = STRING;
            str.set(bits, offset, 0);
            continue MAIN_LOOP;
          }
          // fallthrough to NUMBER
        // ---------------------------------------------------------------------
        case NUMBER:
          if ((c >= '0') && (c <= '9')) {
            if (number >= LARGEST_DIGIT_NUMBER)  state = NUMBER_SKIP;
            else  number = (number*10)+(c-'0');
            break;
          } else if (c == CHAR_DECIMAL_SEP) {
            state = NUMBER_FRACTION;
            fractionDigits = offset;
            decimal = true;
            break;
          } else if ((c == 'e') || (c == 'E')) {
            state = NUMBER_EXP_START;
            sgn_exp = 1;
            break;
          }
          if (exp == -1) {
            number = -number;
          }
          exp = 0;
          // fallthrough to COND_QUOTED_NUMBER_END
        // ---------------------------------------------------------------------
        case COND_QUOTED_NUMBER_END:
          if ( c == quotes) {
            state = NUMBER_END;
            quotes = 0;
            break;
          }
          // fallthrough NUMBER_END
        case NUMBER_END:

          // forced
          if (forcedString || forcedEnum ) {
            state = STRING;
            offset = tokenStart - 1;
            str.set(bits, tokenStart, 0);
            break; // parse as String token now
          }

          if (c == CHAR_SEPARATOR && quotes == 0) {
            exp = exp - fractionDigits;
            dout.addNumCol(colIdx,number,exp);
            ++colIdx;
            // do separator state here too
            state = WHITESPACE_BEFORE_TOKEN;
            break;
          } else if (isEOL(c)) {
            exp = exp - fractionDigits;
            dout.addNumCol(colIdx,number,exp);
            // do EOL here for speedup reasons
            colIdx = 0;
            dout.newLine();
            state = (c == CHAR_CR) ? EXPECT_COND_LF : POSSIBLE_EMPTY_LINE;
            if( !firstChunk )
              break MAIN_LOOP; // second chunk only does the first row
            break;
          } else if ((c == '%')) {
            state = NUMBER_END;
            exp -= 2;
            break;
          } else if ((c != CHAR_SEPARATOR) && ((c == CHAR_SPACE) || (c == CHAR_TAB))) {
            state = NUMBER_END;
            break;
          } else {
            state = STRING;
            offset = tokenStart-1;
            str.set(bits, tokenStart, 0);
            break; // parse as String token now
          }
        // ---------------------------------------------------------------------
        case NUMBER_SKIP:
          if ((c >= '0') && (c <= '9')) {
            exp++;
            break;
          } else if (c == CHAR_DECIMAL_SEP) {
            state = NUMBER_SKIP_NO_DOT;
            break;
          } else if ((c == 'e') || (c == 'E')) {
            state = NUMBER_EXP_START;
            sgn_exp = 1;
            break;
          }
          state = COND_QUOTED_NUMBER_END;
          continue MAIN_LOOP;
        // ---------------------------------------------------------------------
        case NUMBER_SKIP_NO_DOT:
          if ((c >= '0') && (c <= '9')) {
            break;
          } else if ((c == 'e') || (c == 'E')) {
            state = NUMBER_EXP_START;
            sgn_exp = 1;
            break;
          }
          state = COND_QUOTED_NUMBER_END;
          continue MAIN_LOOP;
        // ---------------------------------------------------------------------
        case NUMBER_FRACTION:
          if ((c >= '0') && (c <= '9')) {
            if (number >= LARGEST_DIGIT_NUMBER) {
              if (decimal)
                fractionDigits = offset - 1 - fractionDigits;
              if (exp == -1) number = -number;
              exp = 0;
              state = NUMBER_SKIP_NO_DOT;
            } else {
              number = (number*10)+(c-'0');
            }
            break;
          } else if ((c == 'e') || (c == 'E')) {
            if (decimal)
              fractionDigits = offset - 1 - fractionDigits;
            state = NUMBER_EXP_START;
            sgn_exp = 1;
            break;
          }
          state = COND_QUOTED_NUMBER_END;
          if (decimal)
            fractionDigits = offset - fractionDigits-1;
          if (exp == -1) {
            number = -number;
          }
          exp = 0;
          continue MAIN_LOOP;
        // ---------------------------------------------------------------------
        case NUMBER_EXP_START:
          if (exp == -1) {
            number = -number;
          }
          exp = 0;
          if (c == '-') {
            sgn_exp *= -1;
            break;
          } else if (c == '+'){
            break;
          }
          if ((c < '0') || (c > '9')){
            state = STRING;
            offset = tokenStart-1;
            str.set(bits, tokenStart, 0);
            break; // parse as String token now
          }
          state = NUMBER_EXP;  // fall through to NUMBER_EXP
        // ---------------------------------------------------------------------
        case NUMBER_EXP:
          if ((c >= '0') && (c <= '9')) {
            exp = (exp*10)+(c-'0');
            break;
          }
          exp *= sgn_exp;
          state = COND_QUOTED_NUMBER_END;
          continue MAIN_LOOP;

        // ---------------------------------------------------------------------
        case COND_QUOTE:
          if (c == quotes) {
            str.addChar();
            state = STRING;
            break;
          } else {
            quotes = 0;
            state = STRING_END;
            continue MAIN_LOOP;
          }
        // ---------------------------------------------------------------------
        default:
          assert (false) : " We have wrong state "+state;
      } // end NEXT_CHAR
//      System.out.print(String.format("%c",bits[offset]));
      ++offset; // do not need to adjust for offset increase here - the offset is set to tokenStart-1!
      if (offset < 0) {         // Offset is negative?
        assert !firstChunk;     // Caused by backing up from 2nd chunk into 1st chunk
        firstChunk = true;
        bits = bits0;
        offset += bits.length;
        str.set(bits, offset, 0);
      } else if (offset >= bits.length) { // Off end of 1st chunk?  Parse into 2nd chunk
        // Attempt to get more data.
        if( firstChunk && bits1 == null )
          bits1 = din.getChunkData(cidx+1);
        // if we can't get further we might have been the last one and we must
        // commit the latest guy if we had one.
        if( !firstChunk || bits1 == null ) { // No more data available or allowed
          // If we are mid-parse of something, act like we saw a LF to end the
          // current token.
          if ((state != EXPECT_COND_LF) && (state != POSSIBLE_EMPTY_LINE)) {
            c = CHAR_LF;  continue; // MAIN_LOOP;
          }
          break; // MAIN_LOOP;      // Else we are just done
        }

        // Now parsing in the 2nd chunk.  All offsets relative to the 2nd chunk start.
        firstChunk = false;
        if (state == NUMBER_FRACTION)
          fractionDigits -= bits.length;
        offset -= bits.length;
        tokenStart -= bits.length;
        bits = bits1;           // Set main parsing loop bits
        if( bits[0] == CHAR_LF && state == EXPECT_COND_LF )
          break; // MAIN_LOOP; // when the first character we see is a line end
      }
      c = bits[offset];
      if(isEOL(c) && state != COND_QUOTE && quotes != 0) // quoted string having newline character => fail the line!
        state = EOL;

    } // end MAIN_LOOP
    if (colIdx == 0)
      dout.rollbackLine();
    // If offset is still validly within the buffer, save it so the next pass
    // can start from there.
    if( offset+1 < bits.length ) {
      if( state == EXPECT_COND_LF && bits[offset+1] == CHAR_LF ) offset++;
      if( offset+1 < bits.length ) din.setChunkDataStart(cidx+1, offset+1 );
    }
    return dout;
  }

  @Override protected int fileHasHeader(byte[] bits, ParseSetup ps) {
    boolean hasHdr = true;
    String[] lines = getFirstLines(bits);
    if (lines != null && lines.length > 0) {
      String[] firstLine = determineTokens(lines[0], _setup._separator, _setup._single_quotes);
      if (_setup._column_names != null) {
        for (int i = 0; hasHdr && i < _setup._column_names.length; ++i)
          hasHdr = _setup._column_names[i].equalsIgnoreCase(firstLine[i]);
      } else { // declared to have header, but no column names provided, assume header exist in all files
        _setup._column_names = firstLine;
      }
    } else System.out.println("Foo"); //FIXME Throw exception
    return hasHdr ? ParseSetup.HAS_HEADER: ParseSetup.NO_HEADER;
    // consider making insensitive to quotes
  }

  // ==========================================================================
  /** Separators recognized by the CSV parser.  You can add new separators to
   *  this list and the parser will automatically attempt to recognize them.
   *  In case of doubt the separators are listed in descending order of
   *  probability, with space being the last one - space must always be the
   *  last one as it is used if all other fails because multiple spaces can be
   *  used as a single separator.
   */
  static final byte HIVE_SEP = 0x1; // '^A',  Hive table column separator
  private static byte[] separators = new byte[] { HIVE_SEP, ',', ';', '|', '\t',  ' '/*space is last in this list, because we allow multiple spaces*/ };

  /** Dermines the number of separators in given line.  Correctly handles quoted tokens. */
  private static int[] determineSeparatorCounts(String from, byte singleQuote) {
    int[] result = new int[separators.length];
    byte[] bits = from.getBytes();
    boolean in_quote = false;
    for( byte c : bits ) {
      if( (c == singleQuote) || (c == CsvParser.CHAR_DOUBLE_QUOTE) )
        in_quote ^= true;
      if( !in_quote || c == HIVE_SEP )
        for( int i = 0; i < separators.length; ++i )
          if( c == separators[i] )
            ++result[i];
    }
    return result;
  }

  /** Determines the tokens that are inside a line and returns them as strings
   *  in an array.  Assumes the given separator.
   */
  public static String[] determineTokens(String from, byte separator, boolean singleQuotes) {
    final byte singleQuote = singleQuotes ? CsvParser.CHAR_SINGLE_QUOTE : -1;
    return determineTokens(from, separator, singleQuote);
  }
  public static String[] determineTokens(String from, byte separator, byte singleQuote) {
    ArrayList<String> tokens = new ArrayList<>();
    byte[] bits = from.getBytes();
    int offset = 0;
    int quotes = 0;
    while (offset < bits.length) {
      while ((offset < bits.length) && (bits[offset] == CsvParser.CHAR_SPACE)) ++offset; // skip first whitespace
      if(offset == bits.length)break;
      StringBuilder t = new StringBuilder();
      byte c = bits[offset];
      if ((c == CsvParser.CHAR_DOUBLE_QUOTE) || (c == singleQuote)) {
        quotes = c;
        ++offset;
      }
      while (offset < bits.length) {
        c = bits[offset];
        if ((c == quotes)) {
          ++offset;
          if ((offset < bits.length) && (bits[offset] == c)) {
            t.append((char)c);
            ++offset;
            continue;
          }
          quotes = 0;
        } else if( quotes == 0 && ((c == separator) || CsvParser.isEOL(c)) ) {
          break;
        } else {
          t.append((char)c);
          ++offset;
        }
      }
      c = (offset == bits.length) ? CsvParser.CHAR_LF : bits[offset];
      tokens.add(t.toString());
      if( CsvParser.isEOL(c) || (offset == bits.length) )
        break;
      if (c != separator)
        return new String[0]; // an error
      ++offset;               // Skip separator
    }
    // If we have trailing empty columns (split by separators) such as ",,\n"
    // then we did not add the final (empty) column, so the column count will
    // be down by 1.  Add an extra empty column here
    if( bits.length > 0 && bits[bits.length-1] == separator  && bits[bits.length-1] != CsvParser.CHAR_SPACE)
      tokens.add("");
    return tokens.toArray(new String[tokens.size()]);
  }


  public static byte guessSeparator(String l1, String l2, boolean singleQuotes) {
    final byte single_quote = singleQuotes ? CsvParser.CHAR_SINGLE_QUOTE : -1;
    int[] s1 = determineSeparatorCounts(l1, single_quote);
    int[] s2 = determineSeparatorCounts(l2, single_quote);
    // Now we have the counts - if both lines have the same number of
    // separators the we assume it is the separator.  Separators are ordered by
    // their likelyhoods.
    int max = 0;
    for( int i = 0; i < s1.length; ++i ) {
      if( s1[i] == 0 ) continue;   // Separator does not appear; ignore it
      if( s1[max] < s1[i] ) max=i; // Largest count sep on 1st line
      if( s1[i] == s2[i] ) {       // Sep counts are equal?
        try {
          String[] t1 = determineTokens(l1, separators[i], single_quote);
          String[] t2 = determineTokens(l2, separators[i], single_quote);
          if( t1.length != s1[i]+1 || t2.length != s2[i]+1 )
            continue;           // Token parsing fails
          return separators[i];
        } catch( Exception ignore ) { /*pass; try another parse attempt*/ }
      }
    }
    // No sep's appeared, or no sep's had equal counts on lines 1 & 2.  If no
    // separators have same counts, the largest one will be used as the default
    // one.  If there's no largest one, space will be used.
    if( s1[max]==0 ) max=separators.length-1; // Try last separator (space)
    if( s1[max]!=0 ) {
      String[] t1 = determineTokens(l1, separators[max], single_quote);
      String[] t2 = determineTokens(l2, separators[max], single_quote);
      if( t1.length == s1[max]+1 && t2.length == s2[max]+1 )
        return separators[max];
    }

    return GUESS_SEP;
  }

  // Guess number of columns
  public static int guessNcols( String[] columnNames, String[][] data ) {
    if( columnNames != null ) return data[0].length;
    int longest = 0;            // Longest line
    for( String[] s : data ) if( s.length > longest ) longest = s.length;
    if( longest == data[0].length ) 
      return longest; // 1st line is longer than all the rest; take it

    // we don't have lines of same length, pick the most common length
    int lengths[] = new int[longest+1];
    for( String[] s : data ) lengths[s.length]++;
    int maxCnt = 0;             // Most common line length
    for( int i=0; i<=longest; i++ ) if( lengths[i] > lengths[maxCnt] ) maxCnt = i;
    return maxCnt;
  }

  /** Determines the CSV parser setup from the first few lines.  Also parses
   *  the next few lines, tossing out comments and blank lines.
   *
   *  If the separator is GUESS_SEP, then it is guessed by looking at tokenization
   *  and column count of the first few lines.
   *
   *  If ncols is -1, then it is guessed similarly to the separator.
   *
   *  singleQuotes is honored in all cases (and not guessed).
   *
   */
  static ParseSetup guessSetup(byte[] bits, byte sep, int ncols, boolean singleQuotes, int checkHeader, String[] columnNames, byte[] columnTypes, String[] naStrings) {

    String[] lines = getFirstLines(bits);
    if(lines.length==0 )
      return new ParseSetup(false,0, new String[]{"No data!"},ParserType.AUTO, GUESS_SEP,false,checkHeader,0,null,null,null, null, null, FileVec.DFLT_CHUNK_SIZE);

    // Guess the separator, columns, & header
    ArrayList<String> errors = new ArrayList<>();
    String[] labels;
    final String[][] data = new String[lines.length][];
    if( lines.length == 1 ) {       // Ummm??? Only 1 line?
      if( sep == GUESS_SEP) {
        if (lines[0].split(",").length > 2) sep = (byte) ',';
        else if (lines[0].split(" ").length > 2) sep = ' ';
        else { //one item, guess type
          data[0] = new String[]{lines[0]};
          byte[] ctypes = new byte[1];
          String[][] domains = new String[1][];
          if (NumberUtils.isNumber(data[0][0])) {
            ctypes[0] = Vec.T_NUM;
          } else { // non-numeric
            ValueString str = new ValueString(data[0][0]);
            if (ParseTime.isDateTime(str))
              ctypes[0] = Vec.T_TIME;
            else if (ParseTime.isUUID(str))
                ctypes[0] = Vec.T_UUID;
            else { // give up and guess enum
                ctypes[0] = Vec.T_ENUM;
                domains[0] = new String[]{data[0][0]};
            }
          }
          //FIXME should set warning message and let fall through
          return new ParseSetup(true, 0, new String[]{"Failed to guess separator."}, ParserType.CSV, GUESS_SEP, singleQuotes, checkHeader, 1, null, ctypes, domains, naStrings, data, FileVec.DFLT_CHUNK_SIZE);
        }
      }
      data[0] = determineTokens(lines[0], sep, singleQuotes);
      ncols = (ncols > 0) ? ncols : data[0].length;
      if( checkHeader == GUESS_HEADER) {
        if (ParseSetup.allStrings(data[0])) {
          labels = data[0];
          checkHeader = HAS_HEADER;
        } else {
          labels = null;
          checkHeader = NO_HEADER;
        }
      }
      else if( checkHeader == HAS_HEADER ) labels = data[0];
      else labels = null;
    } else {                    // 2 or more lines

      // First guess the field separator by counting occurrences in first few lines
      if( sep == GUESS_SEP) {   // first guess the separator
        sep = guessSeparator(lines[0], lines[1], singleQuotes);
        if( sep == GUESS_SEP && lines.length > 2 ) {
          sep = guessSeparator(lines[1], lines[2], singleQuotes);
          if( sep == GUESS_SEP) sep = guessSeparator(lines[0], lines[2], singleQuotes);
        }
        if( sep == GUESS_SEP) sep = (byte)' '; // Bail out, go for space
      }

      // Tokenize the first few lines using the separator
      for( int i = 0; i < lines.length; ++i )
        data[i] = determineTokens(lines[i], sep, singleQuotes );
      // guess columns from tokenization
      ncols = guessNcols(columnNames,data);

      // Asked to check for a header, so see if 1st line looks header-ish
      if( checkHeader == HAS_HEADER
        || ( checkHeader == GUESS_HEADER && ParseSetup.hasHeader(data[0], data[1]) && data[0].length == ncols)) {
        checkHeader = HAS_HEADER;
        labels = data[0];
      } else {
        checkHeader = NO_HEADER;
        labels = null;
      }

      // See if compatible headers
      if( columnNames != null && labels != null ) {
        if( labels.length != columnNames.length )
          errors.add("Already have "+columnNames.length+" column labels, but found "+labels.length+" in this file");
        else {
          for( int i = 0; i < labels.length; ++i )
            if( !labels[i].equalsIgnoreCase(columnNames[i]) ) {
              errors.add("Column "+(i+1)+" label '"+labels[i]+"' does not match '"+columnNames[i]+"'");
              break;
            }
          labels = columnNames; // Keep prior case & count in any case
        }
      }
    }

    // Count broken lines; gather error messages
    int ilines = 0;
    for( int i = 0; i < data.length; ++i ) {
      if( data[i].length != ncols ) {
        errors.add("error at line " + i + " : incompatible line length. Got " + data[i].length + " columns.");
        ++ilines;
      }
    }
    String[] err = null;
    if( !errors.isEmpty() )
      errors.toArray(err = new String[errors.size()]);

    // Assemble the setup understood so far
    ParseSetup resSetup = new ParseSetup(true, ilines, err, ParserType.CSV, sep, singleQuotes, checkHeader, ncols, labels, null, null /*domains*/, naStrings, data);

    // now guess the types
    if (columnTypes == null || ncols != columnTypes.length) {
      InputStream is = new ByteArrayInputStream(bits);
      CsvParser p = new CsvParser(resSetup);
      PreviewParseWriter dout = new PreviewParseWriter(resSetup._number_columns);
      try {
        p.streamParse(is, dout);
        resSetup._column_previews = dout;
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    } else {
      resSetup._column_types = columnTypes;
      resSetup._na_strings = null;
    }

    // Return the final setup
    return resSetup;
  }

  private static String[] getFirstLines(byte[] bits) {
    // Parse up to 10 lines (skipping hash-comments & ARFF comments)
    String[] lines = new String[10]; // Parse 10 lines
    int nlines = 0;
    int offset = 0;
    while( offset < bits.length && nlines < lines.length ) {
      int lineStart = offset;
      while( offset < bits.length && !CsvParser.isEOL(bits[offset]) ) ++offset;
      int lineEnd = offset;
      ++offset;
      // For Windoze, skip a trailing LF after CR
      if( (offset < bits.length) && (bits[offset] == CsvParser.CHAR_LF)) ++offset;
      if( bits[lineStart] == '#') continue; // Ignore      comment lines
      if( bits[lineStart] == '%') continue; // Ignore ARFF comment lines
      if( bits[lineStart] == '@') continue; // Ignore ARFF lines
      if( lineEnd > lineStart ) {
        String str = new String(bits, lineStart,lineEnd-lineStart).trim();
        if( !str.isEmpty() ) lines[nlines++] = str;
      }
    }
    return Arrays.copyOf(lines, nlines);
  }

}

package com.maddyhome.idea.vim.extension.argtextobj;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.actionSystem.EditorActionHandler;
import com.maddyhome.idea.vim.VimPlugin;
import com.maddyhome.idea.vim.action.VimCommandAction;
import com.maddyhome.idea.vim.action.motion.TextObjectAction;
import com.maddyhome.idea.vim.command.Argument;
import com.maddyhome.idea.vim.command.Command;
import com.maddyhome.idea.vim.command.MappingMode;
import com.maddyhome.idea.vim.common.TextRange;
import com.maddyhome.idea.vim.extension.VimNonDisposableExtension;
import com.maddyhome.idea.vim.handler.TextObjectActionHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.List;
import java.util.Set;
import java.util.Stack;

public class VimArgTextObjExtension extends VimNonDisposableExtension {

  @NotNull @Override public String getName() { return "argtextobj"; }

  /**
   * A text object for an argument to a function definition or a call.
   */
  @SuppressWarnings("ComponentNotRegistered") // Manually registered
  public static class ArgumentAction extends TextObjectAction {
    ArgumentAction(boolean isInner) {
      super(new Handler(isInner));
    }

    private static class Handler extends TextObjectActionHandler {
      final boolean isInner;

      private Handler(boolean isInner) {
        this.isInner = isInner;
      }

      public TextRange getRange(@NotNull Editor editor,
                                DataContext context,
                                int count,
                                int rawCount,
                                Argument argument) {
        final ArgBoundsFinder finder = new ArgBoundsFinder(editor.getDocument());
        int pos = editor.getCaretModel().getOffset();
        final int selStart = editor.getSelectionModel().getSelectionStart();
        final int selEnd = editor.getSelectionModel().getSelectionEnd();
        if (selStart != selEnd) {
          pos = Math.min(selStart, selEnd);
        }
        int left = 0;
        for (int i = 0; i < count; ++i) {
          if (!finder.findBoundsAt(pos)) {
            VimPlugin.indicateError();
            return null;
          }
          if (isInner && (i == 0 || i == count - 1)) {
            finder.adjustForInner();
          } else {
            finder.adjustForOuter();
          }
          if (i == 0) {
            left = finder.getLeftBound();
          }
          pos = finder.getRightBound();
        }
        return new TextRange(left, finder.getRightBound() - 1);
      }
    }
  }

  private static class VimArgumentCommandAction extends VimCommandAction {
    final private Set<List<KeyStroke>> keySet;

    VimArgumentCommandAction(@NotNull EditorActionHandler defaultHandler, @NotNull String keyString) {
      super(defaultHandler);
      this.keySet = parseKeysSet(keyString);
    }

    @NotNull
    @Override
    public Set<MappingMode> getMappingModes() {
      return MappingMode.VO;
    }

    @NotNull
    @Override
    public Set<List<KeyStroke>> getKeyStrokesSet() {
      return keySet;
    }

    @NotNull
    @Override
    public Command.Type getType() {
      return Command.Type.MOTION;
    }

    @Override
    public int getFlags() {
      return Command.FLAG_MOT_CHARACTERWISE | Command.FLAG_MOT_INCLUSIVE | Command.FLAG_TEXT_BLOCK;
    }

  }

  @Override
  protected void initOnce() {
    addTextObject(new ArgumentAction(true), "VimArgTextObjMotionTextInnerArgument", "ia");
    addTextObject(new ArgumentAction(false), "VimArgTextObjMotionTextOuterArgument", "aa");
  }

  private void addTextObject(@NotNull ArgumentAction innerArgumentAction, @NotNull String actionId, @NotNull String keyString) {
    final ActionManager aMgr = ActionManager.getInstance();
    @Nullable  ArgumentAction anAction = (ArgumentAction)aMgr.getAction(actionId);
    if (anAction == null) {
      anAction = innerArgumentAction;
      aMgr.registerAction(actionId, anAction, VimPlugin.getPluginId());
    }

    VimPlugin.getKey().registerCommandAction(new VimArgumentCommandAction(anAction.getHandler(), keyString), actionId);
  }

  /**
   * Helper class to find argument boundaries starting at the specified
   * position
   */
  private static class ArgBoundsFinder {
    final private CharSequence text;
    final private Document document;
    private int leftBound;
    private int rightBound;
    private int leftBracket;
    private int rightBracket;
    final private static String QUOTES = "\"\'";
    // NOTE: brackets must match by index and ordered by rank.
    final private static String OPEN_BRACKETS = "[{(<";
    final private static String CLOSE_BRACKETS = "]})>";

    ArgBoundsFinder(@NotNull Document document) {
      this.text = document.getImmutableCharSequence();
      this.document = document;
    }

    /**
     * Finds left and right boundaries of an argument at the specified
     * position. If successful @ref getLeftBound() will point to the left
     * argument delimiter and @ref getRightBound() will point to the right
     * argument delimiter. Use @ref adjustForInner or @ref adjustForOuter to
     * fix the boundaries based on the type of text object.
     *
     * @param position starting position.
     */
    boolean findBoundsAt(int position) throws IllegalStateException {
      leftBound = position;
      rightBound = position;
      getOutOfQuotedText();
      if (rightBound == leftBound) {
        if (isCloseBracket(getCharAt(rightBound))) {
          --leftBound;
        } else {
          ++rightBound;
        }
      }
      int nextLeft = leftBound;
      int nextRight = rightBound;
      //
      // Try to extend the bounds until one of the bounds is a comma.
      // This handles cases like: fun(a, (30 + <cursor>x) * 20, c)
      //
      boolean bothBrackets;
      do {
        leftBracket = nextLeft;
        rightBracket = nextRight;
        if (!findOuterBrackets(0, text.length() - 1)) {
          VimPlugin.showMessage("not inside argument list");
          return false;
        }
        leftBound = nextLeft;
        findLeftBound();
        nextLeft = leftBound - 1;
        rightBound = nextRight;
        findRightBound();
        nextRight = rightBound + 1;
        //
        // If reached text boundaries or there is nothing between delimiters.
        //
        if (nextLeft < 0 || nextRight > text.length() || (rightBound - leftBound) == 1) {
          VimPlugin.showMessage("not an argument");
          return false;
        }
        bothBrackets = getCharAt(leftBound) != ',' && getCharAt(rightBound) != ',';
        if (bothBrackets && isIdentPreceding()) {
          // Looking at a pair of brackets preceded by an
          // identifier -- single argument function call.
          break;
        }
      }
      while (leftBound > 0 && rightBound < text.length() && bothBrackets);
      return true;
    }

    /**
     * Skip left delimiter character and any following whitespace.
     */
    void adjustForInner() {
      ++leftBound;
      while (leftBound < rightBound && Character.isWhitespace(getCharAt(leftBound))) {
        ++leftBound;
      }
    }

    /**
     * Exclude left bound character for the first argument, include the
     * right bound character and any following whitespace.
     */
    void adjustForOuter() {
      if (getCharAt(leftBound) != ',') {
        ++leftBound;
        if (rightBound + 1 < rightBracket && getCharAt(rightBound) == ',') {
          ++rightBound;
          while (rightBound + 1 < rightBracket && Character.isWhitespace(getCharAt(rightBound))) {
            ++rightBound;
          }
        }
      }
    }

    int getLeftBound() {
      return leftBound;
    }

    int getRightBound() {
      return rightBound;
    }

    private boolean isIdentPreceding() {
      int i = leftBound - 1;
      // Skip whitespace first.
      while (i > 0 && Character.isWhitespace(getCharAt(i))) {
        --i;
      }
      final int idEnd = i;
      while (i > 0 && Character.isJavaIdentifierPart(getCharAt(i))) {
        --i;
      }
      return (idEnd - i) > 0 && Character.isJavaIdentifierStart(getCharAt(i + 1));
    }


    /**
     * Detects if current position is inside a quoted string and adjusts
     * left and right bounds to the boundaries of the string.
     *
     * @note Does not support line continuations for quoted string ('\' at the end of line).
     */
    private void getOutOfQuotedText() {
      final int lineNo = document.getLineNumber(leftBound);
      final int lineStartOffset = document.getLineStartOffset(lineNo);
      final int lineEndOffset = document.getLineEndOffset(lineNo);
      int i = lineStartOffset;
      while (i <= rightBound) {
        if (isQuote(i)) {
          final int endOfQuotedText = skipQuotedTextForward(i, lineEndOffset);
          if (endOfQuotedText >= leftBound) {
            leftBound = i - 1;
            rightBound = endOfQuotedText + 1;
            break;
          } else {
            i = endOfQuotedText;
          }
        }
        ++i;
      }
    }

    private void findRightBound() {
      while (rightBound < rightBracket) {
        final char ch = getCharAt(rightBound);
        if (ch == ',') {
          break;
        }
        if (isOpenBracket(ch)) {
          rightBound = skipSexp(rightBound, rightBracket, SexpDirection.FORWARD);
        } else {
          if (isQuoteChar(ch)) {
            rightBound = skipQuotedTextForward(rightBound, rightBracket);
          }
          ++rightBound;
        }
      }
    }

    static private char matchingBracket(char ch) {
      int idx = CLOSE_BRACKETS.indexOf(ch);
      if (idx != -1) {
        return OPEN_BRACKETS.charAt(idx);
      } else {
        assert isOpenBracket(ch);
        idx = OPEN_BRACKETS.indexOf(ch);
        return CLOSE_BRACKETS.charAt(idx);
      }
    }

    static private boolean isCloseBracket(final int ch) {
      return CLOSE_BRACKETS.indexOf(ch) != -1;
    }

    static private boolean isOpenBracket(final int ch) {
      return OPEN_BRACKETS.indexOf(ch) != -1;
    }

    private void findLeftBound() {
      while (leftBound > leftBracket) {
        final char ch = getCharAt(leftBound);
        if (ch == ',') {
          break;
        }
        if (isCloseBracket(ch)) {
          leftBound = skipSexp(leftBound, leftBracket, SexpDirection.BACKWARD);
        } else {
          if (isQuoteChar(ch)) {
            leftBound = skipQuotedTextBackward(leftBound, leftBracket);
          }
          --leftBound;
        }
      }
    }

    private boolean isQuote(final int i) {
      return QUOTES.indexOf(getCharAt(i)) != -1;
    }

    static private boolean isQuoteChar(final int ch) {
      return QUOTES.indexOf(ch) != -1;
    }

    private char getCharAt(int logicalOffset) {
      assert logicalOffset < text.length();
      return text.charAt(logicalOffset);
    }

    private int skipQuotedTextForward(final int start, final int end) {
      assert start < end;
      final char quoteChar = getCharAt(start);
      boolean backSlash = false;
      int i = start + 1;

      while (i < end) {
        final char ch = getCharAt(i);
        if (ch == quoteChar && !backSlash) {
          // Found matching quote and it's not escaped.
          break;
        } else {
          backSlash = ch == '\\' && !backSlash;
        }
        ++i;
      }
      return i;
    }

    private int skipQuotedTextBackward(final int start, final int end) {
      assert start > end;
      final char quoteChar = getCharAt(start);
      int i = start - 1;

      while (i > end) {
        final char ch = getCharAt(i);
        final char prevChar = getCharAt(i - 1);
        // NOTE: doesn't handle cases like \\"str", but they make no
        //       sense anyway.
        if (ch == quoteChar && prevChar != '\\') {
          // Found matching quote and it's not escaped.
          break;
        }
        --i;
      }
      return i;
    }

    /**
     * Interface to parametrise S-expression traversal direction.
     */
    abstract static class SexpDirection {
      abstract int delta();

      abstract boolean isOpenBracket(char ch);

      abstract boolean isCloseBracket(char ch);

      abstract int skipQuotedText(int pos, int start, int end, ArgBoundsFinder self);

      static final SexpDirection FORWARD = new SexpDirection() {
        @Override int delta() { return 1; }
        @Override boolean isOpenBracket(char ch) { return ArgBoundsFinder.isOpenBracket(ch); }
        @Override boolean isCloseBracket(char ch) { return ArgBoundsFinder.isCloseBracket(ch); }
        @Override int skipQuotedText(int pos, int start, int end, ArgBoundsFinder self) { return self.skipQuotedTextForward(pos, end); }
      };
      static final SexpDirection BACKWARD = new SexpDirection() {
        @Override int delta() { return -1; }
        @Override boolean isOpenBracket(char ch) { return ArgBoundsFinder.isCloseBracket(ch); }
        @Override boolean isCloseBracket(char ch) { return ArgBoundsFinder.isOpenBracket(ch); }
        @Override int skipQuotedText(int pos, int start, int end, ArgBoundsFinder self) { return self.skipQuotedTextBackward(pos, start); }
      };
    }

    /**
     * Skip over an S-expression considering priorities when unbalanced.
     *
     * @param start position of the starting bracket.
     * @param end   maximum position
     * @param dir   direction instance
     * @return position after the S-expression or the next to the start position if
     * unbalanced.
     */
    private int skipSexp(final int start, final int end, SexpDirection dir) {
      char lastChar = getCharAt(start);
      assert dir.isOpenBracket(lastChar);
      Stack<Character> bracketStack = new Stack<Character>();
      bracketStack.push(lastChar);
      int i = start + dir.delta();
      while (!bracketStack.empty() && i != end) {
        final char ch = getCharAt(i);
        if (dir.isOpenBracket(ch)) {
          bracketStack.push(ch);
        } else {
          if (dir.isCloseBracket(ch)) {
            if (bracketStack.lastElement() == matchingBracket(ch)) {
              bracketStack.pop();
            } else {

              //noinspection StatementWithEmptyBody
              if (getBracketPrio(ch) < getBracketPrio(bracketStack.lastElement())) {
                // (<...) ->  (...)
                bracketStack.pop();
                // Retry the same character again for cases like (...<<...).
                continue;
              } else {                        // Unbalanced brackets -- check ranking.
                // Ignore lower-priority closing brackets.
                // (...> ->  (....
              }
            }
          } else {
            if (isQuoteChar(ch)) {
              i = dir.skipQuotedText(i, start, end, this);
            }
          }
        }
        lastChar = ch;
        i += dir.delta();
      }
      if (bracketStack.empty()) {
        return i;
      } else {
        return start + dir.delta();
      }
    }

    /**
     * @return rank of a bracket.
     */
    static int getBracketPrio(char ch) {
      return Math.max(OPEN_BRACKETS.indexOf(ch), CLOSE_BRACKETS.indexOf(ch));
    }

    /**
     * Find a pair of brackets surrounding (leftBracket..rightBracket) block.
     *
     * @param start minimum position to look for
     * @param end   maximum position
     * @return true if found
     */
    boolean findOuterBrackets(final int start, final int end) {
      boolean hasNewBracket = findPrevOpenBracket(start) && findNextCloseBracket(end);
      while (hasNewBracket) {
        final int leftPrio = getBracketPrio(getCharAt(leftBracket));
        final int rightPrio = getBracketPrio(getCharAt(rightBracket));
        if (leftPrio == rightPrio) {
          // matching brackets
          return true;
        } else {
          if (leftPrio < rightPrio) {
            if (rightBracket + 1 < end) {
              ++rightBracket;
              hasNewBracket = findNextCloseBracket(end);
            } else {
              hasNewBracket = false;
            }
          } else {
            if (leftBracket > 1) {
              --leftBracket;
              hasNewBracket = findPrevOpenBracket(start);
            } else {
              hasNewBracket = false;
            }
          }
        }
      }
      return false;
    }

    /**
     * Finds unmatched open bracket starting at @a leftBracket.
     *
     * @param start minimum position.
     * @return true if found
     */
    private boolean findPrevOpenBracket(final int start) {
      char ch;
      while (!isOpenBracket(ch = getCharAt(leftBracket))) {
        if (isCloseBracket(ch)) {
          leftBracket = skipSexp(leftBracket, start, SexpDirection.BACKWARD);
        } else {
          if (isQuoteChar(ch)) {
            leftBracket = skipQuotedTextBackward(leftBracket, start);
          } else {
            if (leftBracket == start) {
              return false;
            }
          }
          --leftBracket;
        }
      }
      return true;
    }

    /**
     * Finds unmatched close bracket starting at @a rightBracket.
     *
     * @param end maximum position.
     * @return true if found
     */
    private boolean findNextCloseBracket(final int end) {
      char ch;
      while (!isCloseBracket(ch = getCharAt(rightBracket))) {
        if (isOpenBracket(ch)) {
          rightBracket = skipSexp(rightBracket, end, SexpDirection.FORWARD);
        } else {
          if (isQuoteChar(ch)) {
            rightBracket = skipQuotedTextForward(rightBracket, end);
          } else {
            if (rightBracket + 1 == end) {
              return false;
            }
          }
          ++rightBracket;
        }
      }
      return true;
    }
  }

}

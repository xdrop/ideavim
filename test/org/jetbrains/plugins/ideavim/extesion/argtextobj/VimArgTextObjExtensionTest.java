package org.jetbrains.plugins.ideavim.extesion.argtextobj;

import com.maddyhome.idea.vim.command.CommandState;
import org.jetbrains.plugins.ideavim.VimTestCase;

import static com.maddyhome.idea.vim.helper.StringHelper.parseKeys;

public class VimArgTextObjExtensionTest extends VimTestCase {
  @Override
  protected void setUp() throws Exception {
    super.setUp();
    enableExtensions("argtextobj");
  }

  public void testDeleteAnArgument() {
    doTest(parseKeys("daa"),
           "function(int arg1,    char<caret>* arg2=\"a,b,c(d,e)\")",
           "function(int arg1<caret>)");
    doTest(parseKeys("daa"),
           "function(int arg1<caret>)",
           "function(<caret>)");
  }

  public void testChangeInnerArgument() {
    doTest(parseKeys("cia"),
           "function(int arg1,    char<caret>* arg2=\"a,b,c(d,e)\")",
           "function(int arg1,    <caret>)");
    assertMode(CommandState.Mode.INSERT);
  }

  public void testSmartArgumentRecognition() {
    doTest(parseKeys("dia"),
           "function(1, (20<caret>*30)+40, somefunc2(3, 4))",
           "function(1, <caret>, somefunc2(3, 4))");
    doTest(parseKeys("daa"),
           "function(1, (20*30)+40, somefunc2(<caret>3, 4))",
           "function(1, (20*30)+40, somefunc2(<caret>4))");
  }

  public void testIgnoreQuotedArguments() {
    doTest(parseKeys("daa"),
           "function(int arg1,    char* arg2=a,b,c(<caret>arg,e))",
           "function(int arg1,    char* arg2=a,b,c(<caret>e))");
    doTest(parseKeys("daa"),
           "function(int arg1,    char* arg2=\"a,b,c(<caret>arg,e)\")",
           "function(int arg1<caret>)");
  }

  public void testDeleteTwoArguments() {
    doTest(parseKeys("d2aa"),
           "function(int <caret>arg1,    char* arg2=\"a,b,c(d,e)\")",
           "function(<caret>)");
    doTest(parseKeys("d2ia"),
           "function(int <caret>arg1,    char* arg2=\"a,b,c(d,e)\")",
           "function(<caret>)");
  }

  public void testArgumentsInsideAngleBrackets() {
    doTest(parseKeys("dia"),
           "std::vector<int, std::unique_p<caret>tr<bool>> v{};",
           "std::vector<int, <caret>> v{};");
  }

  public void testBracketPriorityToHangleShiftOperators() {
    doTest(parseKeys("dia"),
           "foo(30 << 10, 20 << <caret>3) >> 17",
           "foo(30 << 10, <caret>) >> 17");
    doTest(parseKeys("dia"),
           "foo(30 << <caret>10, 20 * 3) >> 17",
           "foo(<caret>, 20 * 3) >> 17");
  }
}

package autonewsroller.ingest;

import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.*;

/** Conservative dependency-free HTML-to-text extractor. */
public final class ArticleParser {
    private ArticleParser(){}
    public static String extractText(String html) throws IOException {
        if(html==null||html.isBlank())return "";
        StringBuilder out=new StringBuilder();
        ParserDelegator p=new ParserDelegator();
        p.parse(new StringReader(html),new HTMLEditorKit.ParserCallback(){
            int ignore=0;
            @Override public void handleStartTag(HTML.Tag t,MutableAttributeSet a,int pos){ if(t==HTML.Tag.SCRIPT||t==HTML.Tag.STYLE)ignore++; if(ignore==0&&(t==HTML.Tag.P||t==HTML.Tag.BR||t==HTML.Tag.H1||t==HTML.Tag.H2||t==HTML.Tag.H3||t==HTML.Tag.LI))out.append('\n'); }
            @Override public void handleEndTag(HTML.Tag t,int pos){ if(t==HTML.Tag.SCRIPT||t==HTML.Tag.STYLE){if(ignore>0)ignore--;} if(ignore==0&&(t==HTML.Tag.P||t==HTML.Tag.LI))out.append('\n'); }
            @Override public void handleText(char[] data,int pos){ if(ignore==0) out.append(data).append(' '); }
        },true);
        StringBuilder cleaned=new StringBuilder();
        for(String line:out.toString().split("\\R")){line=line.replaceAll("\\s+"," ").trim();if(line.length()>=25&&!line.matches("(?i).*(cookie|privacy policy|sign up|subscribe|advertisement).*"))cleaned.append(line).append('\n');}
        return cleaned.toString().trim();
    }
}

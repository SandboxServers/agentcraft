package dev.agentcraft.mp.state;

public final class MpText {
    private MpText() {}
    public static String sanitize(String text, int cap) {
        if (text == null || text.length() > cap) throw new IllegalArgumentException("string cap exceeded");
        StringBuilder clean = new StringBuilder();
        for (int i=0;i<text.length();) {
            int c=text.codePointAt(i); i+=Character.charCount(c);
            if (c=='§') { if (i<text.length()) i+=Character.charCount(text.codePointAt(i)); continue; }
            int type=Character.getType(c);
            if (Character.isISOControl(c) || type==Character.FORMAT || type==Character.LINE_SEPARATOR ||
                type==Character.PARAGRAPH_SEPARATOR || type==Character.SURROGATE) continue;
            clean.appendCodePoint(c);
        }
        return clean.toString();
    }
}

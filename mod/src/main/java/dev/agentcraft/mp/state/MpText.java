package dev.agentcraft.mp.state;

public final class MpText {
    private MpText() {}
    public static String sanitize(String text, int cap) {
        if (text == null || text.length() > cap) throw new IllegalArgumentException("string cap exceeded");
        StringBuilder clean = new StringBuilder();
        for (int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if (c=='§') { if (i+1<text.length()) i++; continue; }
            if (Character.isISOControl(c) || Character.getType(c)==Character.FORMAT) continue;
            clean.append(c);
        }
        return clean.toString();
    }
}

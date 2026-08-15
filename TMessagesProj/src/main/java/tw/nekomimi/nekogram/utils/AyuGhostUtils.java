package tw.nekomimi.nekogram.utils;
import org.telegram.tgnet.TLRPC;
import tw.nekomimi.nekogram.NekoConfig;

public class AyuGhostUtils {
    private static class AyuStateVariable {
        private final Object sync = new Object();
        public boolean val;
        public int resetAfter;

        public boolean process() {
            synchronized (sync) {
                if (resetAfter == -1) {
                    return val;
                }

                resetAfter -= 1;
                boolean currentVal = val;

                if (resetAfter == 0) {
                    val = false;
                }

                return currentVal;
            }
        }
    }

    private static final AyuStateVariable[] allowReadPacket = new AyuStateVariable[org.telegram.messenger.UserConfig.MAX_ACCOUNT_COUNT];

    static {
        for (int i = 0; i < allowReadPacket.length; i++) {
            allowReadPacket[i] = new AyuStateVariable();
        }
    }

    public static void setAllowReadPacket(int currentAccount, boolean val, int resetAfter) {
        allowReadPacket[currentAccount].val = val;
        allowReadPacket[currentAccount].resetAfter = resetAfter;
    }

    public static boolean getAllowReadPacket(int currentAccount) {
        return tw.nekomimi.nekogram.utils.AyuGhostConfig.sendReadMessagePackets[currentAccount] || allowReadPacket[currentAccount].process();
    }

    public static Long getDialogId(TLRPC.InputPeer peer) {
        long dialogId;
        if (peer.chat_id != 0) {
            dialogId = -peer.chat_id;
        } else if (peer.channel_id != 0) {
            dialogId = -peer.channel_id;
        } else {
            dialogId = peer.user_id;
        }

        return dialogId;
    }

    public static Long getDialogId(TLRPC.InputChannel peer) {
        return -peer.channel_id;
    }
}
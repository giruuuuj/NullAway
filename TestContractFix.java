import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.jetbrains.annotations.Contract;

@NullMarked
class TestContractFix {
    @Contract("!null -> !null")
    public static @Nullable Integer test1(@Nullable String text) {
        if (text != null) {
            return Integer.parseInt(text);
        } else {
            return null;
        }
    }
    
    @Contract("!null -> !null")
    public static @Nullable Integer test2(@Nullable String text) {
        if (text != null) {
            return Integer.parseInt(text);
        }
        return null;
    }
    
    @Contract("!null -> !null")
    public static @Nullable Integer test3(@Nullable String text) {
        return (text != null) ? Integer.parseInt(text) : null;
    }
}

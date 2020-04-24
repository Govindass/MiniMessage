package me.minidigger.minimessage;

import net.kyori.text.Component;
import net.kyori.text.TextComponent;
import net.kyori.text.TextComponent.Builder;
import net.kyori.text.event.ClickEvent;
import net.kyori.text.event.HoverEvent;
import net.kyori.text.format.TextColor;
import net.kyori.text.format.TextDecoration;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;

import static me.minidigger.minimessage.Constants.CLICK;
import static me.minidigger.minimessage.Constants.CLOSE_TAG;
import static me.minidigger.minimessage.Constants.HOVER;
import static me.minidigger.minimessage.Constants.SEPARATOR;
import static me.minidigger.minimessage.Constants.TAG_END;
import static me.minidigger.minimessage.Constants.TAG_START;

public class MiniMessageParser {

    // regex group names
    private static final String START = "start";
    private static final String TOKEN = "token";
    private static final String INNER = "inner";
    private static final String END = "end";
    // https://regex101.com/r/8VZ7uA/5
    private static final Pattern pattern = Pattern.compile("((?<start><)(?<token>([^<>]+)|([^<>]+\"(?<inner>[^\"]+)\"))(?<end>>))+?");

    @Nonnull
    public static String escapeTokens(@Nonnull String richMessage) {
        StringBuilder sb = new StringBuilder();
        Matcher matcher = pattern.matcher(richMessage);
        int lastEnd = 0;
        while (matcher.find()) {
            int startIndex = matcher.start();
            int endIndex = matcher.end();

            if (startIndex > lastEnd) {
                sb.append(richMessage, lastEnd, startIndex);
            }
            lastEnd = endIndex;

            String start = matcher.group(START);
            String token = matcher.group(TOKEN);
            String inner = matcher.group(INNER);
            String end = matcher.group(END);

            // also escape inner
            if (inner != null) {
                token = token.replace(inner, escapeTokens(inner));
            }

            sb.append("\\").append(start).append(token).append("\\").append(end);
        }

        if (richMessage.length() > lastEnd) {
            sb.append(richMessage.substring(lastEnd));
        }

        return sb.toString();
    }

    @Nonnull
    public static String stripTokens(@Nonnull String richMessage) {
        StringBuilder sb = new StringBuilder();
        Matcher matcher = pattern.matcher(richMessage);
        int lastEnd = 0;
        while (matcher.find()) {
            int startIndex = matcher.start();
            int endIndex = matcher.end();

            if (startIndex > lastEnd) {
                sb.append(richMessage, lastEnd, startIndex);
            }
            lastEnd = endIndex;
        }

        if (richMessage.length() > lastEnd) {
            sb.append(richMessage.substring(lastEnd));
        }

        return sb.toString();
    }

    @Nonnull
    public static String handlePlaceholders(@Nonnull String richMessage, @Nonnull String... placeholders) {
        if (placeholders.length % 2 != 0) {
            throw new RuntimeException(
                    "Invalid number placeholders defined, usage: parseFormat(format, key, value, key, value...)");
        }
        for (int i = 0; i < placeholders.length; i += 2) {
            richMessage = richMessage.replace(TAG_START + placeholders[i] + TAG_END, placeholders[i + 1]);
        }
        return richMessage;
    }

    @Nonnull
    public static String handlePlaceholders(@Nonnull String richMessage, @Nonnull Map<String, String> placeholders) {
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            richMessage = richMessage.replace(TAG_START + entry.getKey() + TAG_END, entry.getValue());
        }
        return richMessage;
    }

    @Nonnull
    public static Component parseFormat(@Nonnull String richMessage, @Nonnull String... placeholders) {
        return parseFormat(handlePlaceholders(richMessage, placeholders));
    }

    @Nonnull
    public static Component parseFormat(@Nonnull String richMessage, @Nonnull Map<String, String> placeholders) {
        return parseFormat(handlePlaceholders(richMessage, placeholders));
    }

    @Nonnull
    public static Component parseFormat(@Nonnull String richMessage) {
        Builder parent = TextComponent.builder("");

        Deque<ClickEvent> clickEvents = new ArrayDeque<>();
        Deque<HoverEvent> hoverEvents = new ArrayDeque<>();
        Deque<TextColor> colors = new ArrayDeque<>();
        EnumSet<HelperTextDecoration> decorations = EnumSet.noneOf(HelperTextDecoration.class);

        Matcher matcher = pattern.matcher(richMessage);
        int lastEnd = 0;
        while (matcher.find()) {
            Builder current = null;
            int startIndex = matcher.start();
            int endIndex = matcher.end();

            String msg = null;
            if (startIndex > lastEnd) {
                msg = richMessage.substring(lastEnd, startIndex);
            }
            lastEnd = endIndex;

            // handle message
            if (msg != null && msg.length() != 0) {
                // append message
                current = TextComponent.builder(msg);

                // set everything that is not closed yet
                if (!clickEvents.isEmpty()) {
                    current.clickEvent(clickEvents.peek());
                }
                if (!hoverEvents.isEmpty()) {
                    current.hoverEvent(hoverEvents.peek());
                }
                if (!colors.isEmpty()) {
                    current.color(colors.peek());
                }
                if (!decorations.isEmpty()) {
                    // no lambda because builder isn't effective final :/
                    for (HelperTextDecoration decor : decorations) {
                        decor.apply(current);
                    }
                }
            }

//			String group = matcher.group();
//			String start = matcher.group(START);
            String token = matcher.group(TOKEN);
            String inner = matcher.group(INNER);
//			String end = matcher.group(END);

            Optional<HelperTextDecoration> deco;
            Optional<TextColor> color;

            // click
            if (token.startsWith(CLICK + SEPARATOR)) {
                clickEvents.push(handleClick(token, inner));
            } else if (token.equals(CLOSE_TAG + CLICK)) {
                clickEvents.pop();
            }
            // hover
            else if (token.startsWith(HOVER + SEPARATOR)) {
                hoverEvents.push(handleHover(token, inner));
            } else if (token.equals(CLOSE_TAG + HOVER)) {
                hoverEvents.pop();
            }
            // decoration
            else if ((deco = resolveDecoration(token)).isPresent()) {
                decorations.add(deco.get());
            } else if (token.startsWith(CLOSE_TAG) && (deco = resolveDecoration(token.replace(CLOSE_TAG, ""))).isPresent()) {
                decorations.remove(deco.get());
            }
            // color
            else if ((color = resolveColor(token)).isPresent()) {
                colors.push(color.get());
            } else if (token.startsWith(CLOSE_TAG) && resolveColor(token.replace(CLOSE_TAG, "")).isPresent()) {
                colors.pop();
            } else {
                // invalid tag
                current = TextComponent.builder(TAG_START + token + TAG_END);
            }

            if (current != null) {
                parent.append(current.build());
            }
        }

        // handle last message part
        if (richMessage.length() > lastEnd) {
            String msg = richMessage.substring(lastEnd);
            // append message
            Builder current = TextComponent.builder(msg);

            // set everything that is not closed yet
            if (!clickEvents.isEmpty()) {
                current.clickEvent(clickEvents.peek());
            }
            if (!hoverEvents.isEmpty()) {
                current.hoverEvent(hoverEvents.peek());
            }
            if (!colors.isEmpty()) {
                current.color(colors.peek());
            }
            if (!decorations.isEmpty()) {
                // no lambda because builder isn't effective final :/
                for (HelperTextDecoration decor : decorations) {
                    decor.apply(current);
                }
            }

            parent.append(current.build());
        }

        // optimization, ignore empty parent
        TextComponent comp = parent.build();
        if (comp.content().equals("") && comp.children().size() == 1) {
            return comp.children().get(0);
        } else {
            return comp;
        }
    }

    @Nonnull
    private static ClickEvent handleClick(@Nonnull String token, @Nonnull String inner) {
        String[] args = token.split(SEPARATOR);
        if (args.length < 2) {
            throw new RuntimeException("Can't parse click action (too few args) " + token);
        }
        ClickEvent.Action action = ClickEvent.Action.valueOf(args[1].toUpperCase());
        return ClickEvent.of(action, token.replace(CLICK + SEPARATOR + args[1] + SEPARATOR, ""));
    }

    @Nonnull
    private static HoverEvent handleHover(@Nonnull String token, @Nonnull String inner) {
        String[] args = token.split(SEPARATOR);
        if (args.length < 2) {
            throw new RuntimeException("Can't parse hover action (too few args) " + token);
        }
        HoverEvent.Action action = HoverEvent.Action.valueOf(args[1].toUpperCase());
        return HoverEvent.of(action, parseFormat(inner));
    }

    @Nonnull
    private static Optional<TextColor> resolveColor(@Nonnull String token) {
        try {
            return Optional.of(TextColor.valueOf(token.toUpperCase()));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    @Nonnull
    private static Optional<HelperTextDecoration> resolveDecoration(@Nonnull String token) {
        try {
            return Optional.of(HelperTextDecoration.valueOf(token.toUpperCase()));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    enum HelperTextDecoration {
        BOLD(b -> b.decoration(TextDecoration.BOLD, true)),
        ITALIC(b -> b.decoration(TextDecoration.ITALIC, true)),
        UNDERLINED(b -> b.decoration(TextDecoration.UNDERLINED, true)),
        STRIKETHROUGH(b -> b.decoration(TextDecoration.STRIKETHROUGH, true)),
        OBFUSCATED(b -> b.decoration(TextDecoration.OBFUSCATED, true));

        private final Consumer<Builder> builder;

        HelperTextDecoration(@Nonnull Consumer<Builder> builder) {
            this.builder = builder;
        }

        public void apply(@Nonnull Builder comp) {
            builder.accept(comp);
        }
    }
}
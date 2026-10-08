package de.neemann.digital.lang;

import de.neemann.gui.language.Bundle;
import de.neemann.gui.language.Resources;
import junit.framework.TestCase;

import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Prevents missing translations and broken arguments in the shipped Russian UI. */
public class TestRussianLanguage extends TestCase {
    private static final Pattern ARGUMENT = Pattern.compile("\\{(\\d+)(?:,[^{}]*)?}");
    private static final Pattern TOKEN = Pattern.compile("\\[\\[[^]]+]]");

    public void testCompleteCatalogueAndMessageArguments() {
        Bundle bundle = new Bundle("lang/lang");
        Resources english = bundle.getResources("en");
        Resources russian = bundle.getResources("ru");
        Resources reference = new Resources(getClass().getResourceAsStream("/lang/lang_ru_ref.xml"));
        assertEquals(english.getKeys(), russian.getKeys());
        assertEquals(english.getKeys(), reference.getKeys());
        for (String key : english.getKeys()) {
            String source = english.get(key);
            String translated = russian.get(key);
            assertFalse(key, translated.trim().isEmpty());
            assertEquals(key + " reference", source, reference.get(key));
            assertEquals(key + " message arguments", occurrences(ARGUMENT, source), occurrences(ARGUMENT, translated));
            assertEquals(key + " template tokens", occurrences(TOKEN, source), occurrences(TOKEN, translated));
            Matcher argument = ARGUMENT.matcher(source);
            int count = 0;
            while (argument.find())
                count = Math.max(count, Integer.parseInt(argument.group(1)) + 1);
            if (count > 0) {
                Object[] values = new Object[count];
                for (int i = 0; i < count; i++)
                    values[i] = "ARGUMENT_" + i;
                String expected = new MessageFormat(source, Locale.ENGLISH).format(values);
                String actual = new MessageFormat(translated, Locale.forLanguageTag("ru")).format(values);
                for (Object value : values)
                    assertEquals(key + " formatted " + value,
                            expected.contains(value.toString()), actual.contains(value.toString()));
            }
        }
    }

    private static Map<String, Integer> occurrences(Pattern pattern, String text) {
        Map<String, Integer> counts = new HashMap<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find())
            counts.merge(matcher.group(), 1, Integer::sum);
        return counts;
    }
}

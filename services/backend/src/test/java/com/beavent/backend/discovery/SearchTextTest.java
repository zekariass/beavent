package com.beavent.backend.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.beavent.backend.discovery.internal.SearchText;
import org.junit.jupiter.api.Test;

/** How text is prepared for search (docs/06 §5.5): folding, transliteration and the query strings built from it. */
class SearchTextTest {

    @Test
    void lowerCasesRemovesAccentsAndPunctuation() {
        assertThat(SearchText.normalize("  Café  Bole-Atlas, Addis!  ")).isEqualTo("cafe bole atlas addis");
        assertThat(SearchText.normalize(null)).isEmpty();
        assertThat(SearchText.normalize("   ")).isEmpty();
    }

    @Test
    void geezLettersThatSoundTheSameAreFoldedTogether() {
        assertThat(SearchText.normalize("ፀጉር")).isEqualTo(SearchText.normalize("ጸጉር"));
        assertThat(SearchText.normalize("ሠላም")).isEqualTo(SearchText.normalize("ሰላም"));
        assertThat(SearchText.normalize("ሐምሌ")).isEqualTo(SearchText.normalize("ሀምሌ"));
        assertThat(SearchText.normalize("ኀይል")).isEqualTo(SearchText.normalize("ሀይል"));
        assertThat(SearchText.normalize("ዐቢይ")).isEqualTo(SearchText.normalize("አቢይ"));
        // Every vowel order folds, not just the first.
        assertThat(SearchText.normalize("ሒ")).isEqualTo(SearchText.normalize("ሂ"));
        assertThat(SearchText.normalize("ሡ")).isEqualTo(SearchText.normalize("ሱ"));
    }

    @Test
    void geezWordsGetALatinSpelling() {
        assertThat(SearchText.romanize(SearchText.normalize("ቦሌ"))).isEqualTo("bole");
        assertThat(SearchText.romanize(SearchText.normalize("ሰላም"))).isEqualTo("selam");
        assertThat(SearchText.romanize(SearchText.normalize("አዲስ አበባ"))).isEqualTo("adis abeba");
        assertThat(SearchText.romanize(SearchText.normalize("ሳሎን"))).isEqualTo("salon");
        assertThat(SearchText.romanize("bole")).isEmpty();
    }

    @Test
    void aDocumentHoldsEachTextAndTheLatinSpellingOfItsGeezWords() {
        assertThat(SearchText.document(java.util.List.of("ቦሌ ውበት", "Hair Salon")))
                .contains("ቦሌ")
                .contains("bole")
                .contains("hair")
                .contains("salon");
    }

    @Test
    void aQueryMatchesEveryWordAsAPrefixInEitherSpelling() {
        assertThat(SearchText.tsQuery("Bole Salon")).isEqualTo("'bole':* & 'salon':*");
        assertThat(SearchText.tsQuery("ቦሌ")).isEqualTo("('ቦሌ':* | 'bole':*)");
        assertThat(SearchText.tsQuery("  ,.  ")).isEmpty();
        // Nothing a person types can break out of the query syntax.
        assertThat(SearchText.tsQuery("a' | b & !(c):*")).isEqualTo("'a':* & 'b':* & 'c':*");
    }

    @Test
    void theFuzzyTextCarriesTheLatinSpellingOfGeezWords() {
        assertThat(SearchText.fuzzy("ቦሌ ሳሎን")).isEqualTo("ቦሌ ሳሎን bole salon");
        assertThat(SearchText.fuzzy("Bole")).isEqualTo("bole");
    }
}

package com.blossom.backend.server.ai;

import com.blossom.common.base.exception.XzException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiArticleServiceTest {

    private final AiArticleService service = new AiArticleService(null, null, null, null, null, null);

    @Test
    void appliesExactEditsInOrder() {
        AiModels.ExactEdit first = edit("alpha", "beta");
        AiModels.ExactEdit second = edit("beta gamma", "done");

        assertEquals("done", service.applyExactEdits("alpha gamma", Arrays.asList(first, second)));
    }

    @Test
    void rejectsMissingOrNonUniquePatchTargetsWithoutPartialWrite() {
        XzException missing = assertThrows(XzException.class,
                () -> service.applyExactEdits("alpha", Collections.singletonList(edit("missing", "x"))));
        assertEquals("ARTICLE-EDIT-MISMATCH", missing.getCode());

        XzException repeated = assertThrows(XzException.class,
                () -> service.applyExactEdits("same same", Collections.singletonList(edit("same", "x"))));
        assertEquals("ARTICLE-EDIT-MISMATCH", repeated.getCode());
    }

    private AiModels.ExactEdit edit(String oldText, String newText) {
        AiModels.ExactEdit edit = new AiModels.ExactEdit();
        edit.setOldText(oldText);
        edit.setNewText(newText);
        return edit;
    }
}

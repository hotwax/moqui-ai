package org.moqui.ai.rules

import org.moqui.entity.EntityCondition

class ItemChecks {
    static boolean matches(Map match, Map values) {
        return ((EntityCondition) match.condition).mapMatches(values) && ((List<Map>) match.itemChecks).every { holds(it, values) }
    }

    static boolean holds(Map itemCheck, Map values) {
        List<Map> items = (List<Map>) values.get(itemCheck.fieldName) ?: []
        return items.any { Map item -> matches((Map) itemCheck.itemMatch, item) } == itemCheck.found
    }
}

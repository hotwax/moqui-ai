package org.moqui.ai.rules

import org.moqui.context.ExecutionContext
import org.moqui.impl.actions.XmlAction
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ExecutionContextImpl

class FactActions {
    static final String TEMPLATE_LOCATION = "component://moqui-ai/template/FactActions.xml.ftl"

    static XmlAction write(ExecutionContext ec, String factTypeId, List<Map> nodes) {
        StringWriter actionsText = new StringWriter()
        ec.context.push([nodes: nodes])
        try {
            ec.resource.template(TEMPLATE_LOCATION, actionsText)
        } finally {
            ec.context.pop()
        }
        return new XmlAction((ExecutionContextFactoryImpl) ec.factory, actionsText.toString(), "moqui.ai.FactType." + factTypeId)
    }

    static Map run(ExecutionContext ec, Map builtFact, Map parameters) {
        ec.context.push([parameters: parameters ?: [:], fixedValues: builtFact.fixedValues, messages: builtFact.messages])
        try {
            ((XmlAction) builtFact.actions).run((ExecutionContextImpl) ec)
            return ec.message.hasError() ? null : (Map) ec.context.get("fact")
        } finally {
            ec.context.pop()
        }
    }
}

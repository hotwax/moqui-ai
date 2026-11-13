<actions>
    <set field="fact" from="[:]"/>
<#list nodes as node><@.namespace[node.kind] node=node/></#list>
</actions>

<#macro record node>
    <set field="${node.rowName}List" from="[]"/>
    <@readRows node "entity-find" "list=\"${node.rowName}List\" limit=\"2\" cache=\"false\""/>
    <if condition="${node.rowName}List.size() &gt; 1"><message error="true">${'$'}{messages[${node.messageIndex?c}]}</message><return/></if>
    <set field="${node.rowName}" from="${node.rowName}List ? ${node.rowName}List[0] : null"/>
<#list node.children as child><@.namespace[child.kind] node=child/></#list>
</#macro>

<#macro list node>
    <set field="${node.rowName}List" from="[]"/>
    <@readRows node "entity-find" "list=\"${node.rowName}List\" cache=\"false\""/>
    <set field="${node.factName}.${node.fieldName}" from="[]"/>
    <iterate list="${node.rowName}List" entry="${node.rowName}">
        <set field="${node.itemName}" from="[:]"/>
<#list node.children as child><@.namespace[child.kind] node=child/></#list>
        <script>${node.factName}.${node.fieldName}.add(${node.itemName})</script>
    </iterate>
</#macro>

<#macro count node>
    <set field="${node.rowName}Count" from="0"/>
    <@readRows node "entity-find-count" "count-field=\"${node.rowName}Count\""/>
    <@fill node "${node.rowName}Count"/>
</#macro>

<#macro value node>
    <@fill node node.from/>
</#macro>

<#macro age node>
    <@fill node "${node.from} == null ? null : (ec.user.nowTimestamp.time - ${node.from}.time).intdiv(86400000L)"/>
</#macro>

<#macro fill node valueExpression>
    <service-call name="ai.rules.FactValueServices.convert#FactValue" out-map="converted" out-map-add-to-existing="false"
            in-map="[valueTypeEnumId: '${node.valueTypeEnumId}', value: (${valueExpression})]"/>
    <if condition="converted.problem"><message error="true">${'$'}{messages[${node.fillMessageIndex?c}]}: ${'$'}{converted.problem}</message><return/></if>
    <set field="${node.factName}.${node.fieldName}" from="converted.convertedValue"/>
</#macro>

<#macro readRows node findElement findAttributes>
<#if node.parentRowName??>
    <if condition="${node.parentRowName} != null">
</#if>
    <${findElement} entity-name="${node.entityName}" ${findAttributes}>
<#list node.keyFields as keyField>
        <econdition field-name="${keyField.fieldName}" from="${node.parentRowName}.${keyField.parentFieldName}"/>
</#list>
<#list node.conditions as condition>
        <econdition field-name="${condition.fieldName}" operator="${condition.operator}" from="${condition.from}"/>
</#list>
<#if node.filterByDate>
        <date-filter/>
</#if>
    </${findElement}>
<#if node.parentRowName??>
    </if>
</#if>
</#macro>

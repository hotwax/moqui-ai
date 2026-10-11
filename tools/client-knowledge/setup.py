from pathlib import Path
import zipfile, os
base=Path('/Users/adityapatel/Documents/GitHub/moqui/maarg-oms')
r=base/'client-knowledge-poc'; r.mkdir(exist_ok=True); os.chmod(r,0o700)
for p in ['runtime/conf','runtime/component','runtime/db','runtime/log','runtime/txlog','runtime/lib','inbox','lib','webapp']: (r/p).mkdir(parents=True,exist_ok=True)
source=base/'moqui-framework'
for p in ['base-component','lib']:
 dst=r/'runtime'/p
 if p=='lib':
  for j in (source/'runtime/lib').glob('*.jar'):
   if not (dst/j.name).exists(): (dst/j.name).symlink_to(j)
 elif not dst.exists(): dst.symlink_to(source/'runtime'/p)
for p in (source/'runtime/component').iterdir():
 if p.is_dir() and (p/'component.xml').exists() and p.name in ['moqui-ai','oms','maarg-util']:
  dst=r/'runtime/component'/p.name
  if not dst.exists(): dst.symlink_to(p.resolve())
with zipfile.ZipFile(source/'moqui.war') as z:
 for name in z.namelist():
  if (name.startswith('WEB-INF/lib/') or name.startswith('execlib/')) and name.endswith('.jar'):
   dst=r/'lib'/Path(name).name
   if not dst.exists(): dst.write_bytes(z.read(name))
  elif name.startswith('WEB-INF/') and not name.startswith('WEB-INF/lib/') and not name.endswith('/'):
   dst=r/'webapp'/name; dst.parent.mkdir(exist_ok=True,parents=True); dst.write_bytes(z.read(name))
# The webapp uses parent-first class loading, including the already-built local framework jar.
(r/'runtime/conf/KnowledgeConf.xml').write_text('''<moqui-conf>
<default-property name="entity_ds_db_conf" value="h2"/>
<default-property name="entity_ds_database" value="client-knowledge"/>
<default-property name="entity_empty_db_load" value="none"/>
<default-property name="scheduled_job_check_time" value="0"/>
<default-property name="mcp_enabled" value="Y"/>
<default-property name="ai_openai_key" value=""/>
<default-property name="ai_anthropic_key" value=""/>
<tools><tool-factory class="org.moqui.impl.tools.H2ServerToolFactory" disabled="true"/></tools>
<entity-facade><datasource group-name="transactional" startup-add-missing="false" runtime-add-missing="true"/></entity-facade>
<webapp-list><webapp name="webroot" http-host="127.0.0.1" http-port="8097" https-enabled="false"><root-screen host=".*" location="component://webroot/screen/webroot.xml"/></webapp></webapp-list>
</moqui-conf>''')
cp=[str(source/'framework/build/libs/moqui-framework-4.0.0.jar')]+[str(x) for x in (r/'lib').glob('*.jar') if not x.name.startswith('moqui-framework')]+[str(source/'runtime/component/moqui-ai/lib/moqui-ai-1.0.0.jar')]
(r/'classpath.txt').write_text(':'.join(cp))
print('Isolated runtime prepared:',r)

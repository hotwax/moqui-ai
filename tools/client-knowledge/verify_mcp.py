import asyncio,json
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client

async def main():
    async with streamable_http_client('http://127.0.0.1:8097/mcp/json') as (read,write):
        async with ClientSession(read,write) as session:
            init=await session.initialize()
            listed=await session.list_tools()
            names=[t.name for t in listed.tools]
            assert len(names)==6 and 'search_client_knowledge' in names,names
            result=await session.call_tool('search_client_knowledge',{'clientId':'rails','query':'Shopify payment mappings','limit':3})
            assert not result.is_error
            body=result.structured_content
            assert any('/331' in x['sourceUrl'] or '/332' in x['sourceUrl'] for x in body['matches']),body
            docs=await session.call_tool('search_documentation',{'query':'routing','limit':3})
            assert not docs.is_error and docs.structured_content['matches']
            assert all(x['clientId']=='GLOBAL' for x in docs.structured_content['matches'])
            denied=await session.call_tool('search_client_knowledge',{'clientId':'another-client','query':'routing'})
            assert denied.is_error
            print(json.dumps({'mcp_sdk':'passed','server':init.server_info.name,'tools':names,'results':len(body['matches']),'cross_client_denied':True}))
asyncio.run(main())

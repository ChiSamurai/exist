/*
 *  eXist Open Source Native XML Database
 *  Copyright (C) 2011 The eXist Project
 *  http://exist-db.org
 *
 *  This program is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public
 *  License along with this library; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 *
 *  $Id$
 */
package org.exist.security.realm.oauth;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.security.auth.Subject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.security.DefaultIdentityService;
import org.eclipse.jetty.server.Authentication;
import org.eclipse.jetty.server.UserIdentity;
import org.exist.security.AXSchemaType;
import org.exist.security.AbstractAccount;
import org.exist.security.Account;
import org.exist.security.SchemaType;
import org.exist.security.internal.HttpSessionAuthentication;
import org.exist.security.internal.SubjectAccreditedImpl;
import org.json.JSONException;
import org.json.JSONObject;

import net.oauth.exception.OAuthException;
import net.oauth.token.v2.AccessToken;
import net.oauth.util.OAuthUtil;

import com.neurologic.http.HttpClient;
import com.neurologic.http.impl.ApacheHttpClient;
import com.neurologic.oauth.config.ServiceConfig;
import com.neurologic.oauth.service.impl.OAuth2Service;

/**
 * @author <a href="mailto:shabanovd@gmail.com">Dmitriy Shabanov</a>
 * 
 */
public class ServiceFacebook extends OAuth2Service implements OAuth2ServiceAtEXist {

	public static final String FACEBOOK_ACCESS_TOKEN_SESSION = "FACEBOOK_ACCESS_TOKEN_SESSION";

	@Override
	public void saveAccessToken(HttpServletRequest request, AccessToken accessToken) {
		
		HttpClient client = new ApacheHttpClient();
		try {
			InputStream in = client.connect("GET", "https://graph.facebook.com/me?access_token="+accessToken.getAccessToken());
			
			String contentType = client.getResponseHeaderValue("Content-Type");
			if (contentType == null) contentType = "";
			
			String charset = "";
			int semicolonPos = contentType.indexOf(';');
			
			if (semicolonPos > 0) {
				String _charset = contentType.substring(semicolonPos + 1).trim();
				if (_charset.startsWith("charset")) {
					charset = _charset.substring(_charset.indexOf('=') + 1);
				}
				contentType = contentType.substring(0, semicolonPos);
			}
			
			Map<String, String> responseAttributes = null;
			String response = streamToString(in, charset);
			if ("application/json".equals(contentType) || (response.startsWith("{") && response.endsWith("}"))) {
				JSONObject jsonResponse = new JSONObject(response);
				if (jsonResponse != null) {
					if (jsonResponse.has("error")) {
						throw new OAuthException("Error getting access token: " + System.getProperty("line.separator") + jsonResponse.toString());
					}
					
					responseAttributes = parseJSONObject(jsonResponse);
				}
			} else if ("text/plain".equals(contentType) || (response.contains("=") && response.contains("&"))) {
				responseAttributes = OAuthUtil.parseQueryString(response);
			}
			
			String id = responseAttributes.get("id");
			
			String accountName = id + "@facebook.com";

			Account found = OAuthRealm._.getAccount(accountName);
			
			if (found == null) {
				Map<SchemaType, String> metadata = new HashMap<SchemaType, String>();
				metadata.put(FBSchemaType.ID, responseAttributes.get("id"));
				metadata.put(AXSchemaType.FIRSTNAME, responseAttributes.get("first_name"));
				metadata.put(AXSchemaType.LASTNAME, responseAttributes.get("last_name"));
				metadata.put(AXSchemaType.FULLNAME, responseAttributes.get("name"));
				metadata.put(AXSchemaType.TIMEZONE, responseAttributes.get("timezone"));
				
				found = OAuthRealm._.createAccountInDatabase(accountName, metadata);
			}
			
			Account principal = new SubjectAccreditedImpl((AbstractAccount) found, accessToken);
			
	        HttpSession session = request.getSession(true);

			Subject subject = new Subject();

			//TODO: hardcoded to jetty - rewrite
			//*******************************************************
			DefaultIdentityService _identityService = new DefaultIdentityService();
			UserIdentity user = _identityService.newUserIdentity(subject, principal, new String[0]);
            
			Authentication cached=new HttpSessionAuthentication(session, user);
            session.setAttribute(HttpSessionAuthentication.__J_AUTHENTICATED, cached);
			//*******************************************************
			
			
		} catch (Exception e) {
			e.printStackTrace();
			request.setAttribute("exception", e);
		}
		request.getSession().setAttribute(FACEBOOK_ACCESS_TOKEN_SESSION, accessToken);
	}

	ServiceConfig config = null;
	
	@Override
	public void setServiceConfig(ServiceConfig serviceConfig) {
		config = serviceConfig; 
	}
	
	@Override
	public ServiceConfig getServiceConfig() {
		return config;
	}
	
	String redirectUri = null;
	
	@Override
	public String getRedirectUri() {
		return redirectUri;
	}

	public void setRedirectUri(String uri) {
		redirectUri = uri;
	}

	@Override
	protected String[] getScope() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	protected String getScopeDelimiter() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	protected String getState() {
		// TODO Auto-generated method stub
		return null;
	}

	private String streamToString(InputStream stream, String charset) throws IOException {
		if (stream == null) {
			return null;
		}
		
		ByteArrayOutputStream byteArray = new ByteArrayOutputStream();
		int c;
		
		while ((c = stream.read()) != -1) {
			byteArray.write(c);
		}

		return new String(byteArray.toByteArray(), charset);
	}

	private synchronized Map<String, String> parseJSONObject(JSONObject json) throws JSONException {
		Map<String, String> parameters = null;
		
		if (json != null && json.length() > 0) {
			parameters = new LinkedHashMap<String, String>();
			@SuppressWarnings("unchecked")
			Iterator<String> iter = json.keys();
			
			if (iter != null) {
				while (iter.hasNext()) {
					String key = iter.next();
					parameters.put(key, json.getString(key));
				}
			}
		}
		
		return parameters;
	}
	
	public enum FBSchemaType implements SchemaType {

	    ID("https://graph.facebook.com/me/id", "id");

	    private final String namespace;
	    private final String alias;

	    FBSchemaType(String namespace, String alias) {
	        this.namespace = namespace;
	        this.alias = alias;
	    }

	    public static FBSchemaType valueOfNamespace(String namespace) {
	        for(FBSchemaType schemaType : FBSchemaType.values()) {
	            if(schemaType.getNamespace().equals(namespace)) {
	                return schemaType;
	            }
	        }
	        return null;
	    }

	    public static FBSchemaType valueOfAlias(String alias) {
	        for(FBSchemaType schemaType : FBSchemaType.values()) {
	            if(schemaType.getAlias().equals(alias)) {
	                return schemaType;
	            }
	        }
	        return null;
	    }

	    public String getNamespace() {
	        return namespace;
	    }

	    public String getAlias() {
	        return alias;
	    }
	}
}
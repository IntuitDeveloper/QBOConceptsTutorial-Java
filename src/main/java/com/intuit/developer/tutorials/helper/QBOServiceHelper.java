package com.intuit.developer.tutorials.helper;

import java.util.List;

import javax.servlet.http.HttpSession;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.intuit.developer.tutorials.client.OAuth2PlatformClientFactory;
import com.intuit.ipp.core.Context;
import com.intuit.ipp.core.IEntity;
import com.intuit.ipp.core.ServiceType;
import com.intuit.ipp.data.Error;
import com.intuit.ipp.exception.FMSException;
import com.intuit.ipp.exception.InvalidTokenException;
import com.intuit.ipp.security.OAuth2Authorizer;
import com.intuit.ipp.services.DataService;
import com.intuit.ipp.services.QueryResult;
import com.intuit.ipp.services.ReportService;
import com.intuit.ipp.util.Config;
import com.intuit.oauth2.client.OAuth2PlatformClient;
import com.intuit.oauth2.data.BearerTokenResponse;
import com.intuit.oauth2.exception.OAuthException;

@Service
public class QBOServiceHelper {
	
	@Autowired
	OAuth2PlatformClientFactory factory;
	
	private static final Logger logger = Logger.getLogger(QBOServiceHelper.class);

	public DataService getDataService(String realmId, String accessToken) throws FMSException {
		
    	Context context = prepareContext(realmId, accessToken);

		// create dataservice
		return new DataService(context);
	}

	private Context prepareContext(String realmId, String accessToken) throws FMSException {
		String url = factory.getPropertyValue("IntuitAccountingAPIHost") + "/v3/company";

		Config.setProperty(Config.BASE_URL_QBO, url);
		//create oauth object
		OAuth2Authorizer oauth = new OAuth2Authorizer(accessToken);
		//create context
		Context context = new Context(oauth, ServiceType.QBO, realmId);
		return context;
	}
	
	public ReportService getReportService(String realmId, String accessToken) throws FMSException {
		
    	Context context = prepareContext(realmId, accessToken);

		// create dataservice
		return new ReportService(context);
	}
	
	/**
	 * This method is an override with duplication of the QBOServiceHelper
	 * to pass in a different minor version.  This is due to an invoice update
	 * failing with the discount line for minor version "23" (SDK default), hence
	 * overriding with the minor version "4"
	 *
	 * @param realmId
	 * @param accessToken
	 * @return
	 * @throws FMSException
	 */
	public DataService getDataService(String realmId, String accessToken, String minorVersion) throws FMSException {

		Context context = prepareContext(realmId, accessToken);
		context.setMinorVersion(minorVersion);

		// create dataservice
		return new DataService(context);
	}

	
    /**
     * Queries data from QuickBooks
     * 
     * @param session
     * @param sql
     * @return
     */
    public List<? extends IEntity> queryData(HttpSession session, String sql) {

    	String realmId = (String)session.getAttribute("realmId");
    	if (StringUtils.isEmpty(realmId)) {
    		logger.error("Relam id is null ");
    	}
    	String accessToken = (String)session.getAttribute("access_token");
    	
    	try {
        	
        	//get DataService
    		DataService service = getDataService(realmId, accessToken);
			
			// get data
			QueryResult queryResult = service.executeQuery(sql);
			return queryResult.getEntities();
		}
	        /*
	         * Handle 401 status code - 
	         * If a 401 response is received, refresh tokens should be used to get a new access token,
	         * and the API call should be tried again.
	         */
	        catch (InvalidTokenException e) {			
				logger.error("Error while calling executeQuery :: " + e.getMessage());
				
				//refresh tokens
	        	logger.info("received 401 during companyinfo call, refreshing tokens now");
	        	OAuth2PlatformClient client  = factory.getOAuth2PlatformClient();
	        	String refreshToken = (String)session.getAttribute("refresh_token");
	        	
				try {
					BearerTokenResponse bearerTokenResponse = client.refreshToken(refreshToken);
					session.setAttribute("access_token", bearerTokenResponse.getAccessToken());
		            session.setAttribute("refresh_token", bearerTokenResponse.getRefreshToken());
		            
		            //call company info again using new tokens
		            logger.info("calling companyinfo using new tokens");
		            DataService service = getDataService(realmId, accessToken);
					
					// get data
					QueryResult queryResult = service.executeQuery(sql);
					return queryResult.getEntities();
					 
				} catch (OAuthException e1) {
					logger.error("Error while calling bearer token :: " + e.getMessage());
					
				} catch (FMSException e1) {
					logger.error("Error while calling company currency :: " + e.getMessage());
				}
	            
			} catch (FMSException e) {
				List<Error> list = e.getErrorList();
				list.forEach(error -> logger.error("Error while calling executeQuery :: " + error.getMessage()));
			}
		return null;
    }
}

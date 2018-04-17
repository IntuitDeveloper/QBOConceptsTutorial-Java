package com.intuit.developer.tutorials.controller;

import java.util.ArrayList;
import java.util.List;
import javax.servlet.http.HttpSession;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intuit.developer.tutorials.client.OAuth2PlatformClientFactory;
import com.intuit.developer.tutorials.helper.QBOServiceHelper;
import com.intuit.ipp.data.Error;
import com.intuit.ipp.data.Report;
import com.intuit.ipp.exception.FMSException;
import com.intuit.ipp.exception.InvalidTokenException;
import com.intuit.ipp.services.ReportName;
import com.intuit.ipp.services.ReportService;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * @author dderose
 *
 */
@Controller
public class ReportsController {
	
	@Autowired
	OAuth2PlatformClientFactory factory;
	
	@Autowired
    public QBOServiceHelper helper;

	
	private static final Logger logger = Logger.getLogger(ReportsController.class);
	
	
	/**
     * Sample QBO API call using OAuth2 tokens
     * 
     * @param session the HttpSession
     * @return a list of reports in JSON separated by ","
     */
	@ResponseBody
    @RequestMapping("/reports")
    public String callReportsConcept(HttpSession session) {
    	String realmId = (String)session.getAttribute("realmId");
    	if (StringUtils.isEmpty(realmId)) {
    		return new JSONObject().put("response","No realm ID.  QBO calls only work if the accounting scope was passed!").toString();
    	}

    	String accessToken = (String)session.getAttribute("access_token");
        try {
			// get ReportService
			ReportService service = getReportService("2018-01-01", "2018-04-06", "Customers",
					realmId, accessToken);

			List<String> reportNames = new ArrayList<>();
			reportNames.add(ReportName.BALANCESHEET.toString());
			reportNames.add(ReportName.PROFITANDLOSS.toString());

			return getReports(service, reportNames);
		} catch (InvalidTokenException e) {
			return new JSONObject().put("response","InvalidToken - Refreshtoken and try again").toString();
		} catch (FMSException e) {
			List<Error> list = e.getErrorList();
			list.forEach(error -> logger.error("Error while calling the API :: " + error.getMessage()));
			return new JSONObject().put("response","Failed").toString();
		}

    }

	/**
	 * Create a ReportService from given parameters
	 * @param startDate start date of the report
	 * @param endDate end date of the report
	 * @param summarizeCriteria the criteria to sort the report
	 * @param realmId unique identifier for the user
	 * @param accessToken access token to authenticate the user
	 * @return an instance of report service
	 * @throws FMSException throw exception if failed to authenticate the user
	 */
    private ReportService getReportService(String startDate, String endDate, String summarizeCriteria,
										   String realmId, String accessToken) throws FMSException {
		ReportService service = helper.getReportService(realmId, accessToken);

		service.setStart_date(startDate);
		service.setEnd_date(endDate);
		service.setSummarize_column_by(summarizeCriteria);

		service.setAccounting_method("Accrual");

		return service;
	}

	/**
	 * Get a list of reports by the given report names.
	 * @param service the report service
	 * @param reportNames a list of report names
	 * @return a list of reports in JSON format separated by comma.
	 * @throws FMSException if failed to retrieve the report by report name.
	 */
    private String getReports(ReportService service, List<String> reportNames) throws FMSException {
		StringBuilder reports = new StringBuilder();
		for (String reportName : reportNames) {
			Report report = service.executeReport(reportName);
			reports.append(processResponse(report));
			logger.info("ReportName -> name: " + report.getHeader().getReportName().toLowerCase());
		}

		return reports.toString();
	}

	/**
	 * Map an object to a JSON object.
	 * @param entity the entity to map
	 * @return a JSON format of the object.
	 */
	private String processResponse(Object entity) {
		ObjectMapper mapper = new ObjectMapper();
		try {
			String jsonInString = mapper.writeValueAsString(entity);
			return jsonInString;
		} catch (JsonProcessingException e) {
			logger.error("Exception while getting report ", e);
			return new JSONObject().put("response","Failed").toString();
		}
	}
    
}

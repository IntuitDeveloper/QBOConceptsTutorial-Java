package com.intuit.developer.tutorials.controller;

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
     	* @return a report in JSON String format
     	*/
	@ResponseBody
    	@RequestMapping("/reports")
    	public String callReportsConcept(HttpSession session) {
    		String realmId = (String)session.getAttribute("realmId");

    		if (StringUtils.isEmpty(realmId)) {
    			return new JSONObject().put("response","No realm ID.  QBO calls only work if the accounting scope was passed!").toString();
    		}

        	try {
			// get ReportService
			ReportService service = getReportService(session);

			String reportString = getReportString(service, ReportName.BALANCESHEET.toString());
			logger.info("retrieved balance sheet report, " +  reportString);
			reportString = getReportString(service, ReportName.PROFITANDLOSS.toString());
			logger.info("retrieved profit and loss report" + reportString);

			return reportString;
		} catch (InvalidTokenException e) {
        		logger.error("invalid token: ", e);
			return new JSONObject().put("response","InvalidToken - Refreshtoken and try again").toString();
		} catch (FMSException e) {
			List<Error> list = e.getErrorList();
			list.forEach(error -> logger.error("Error while calling the API :: " + error.getMessage()));
			return new JSONObject().put("response","Failed").toString();
		}

    	}

	/**
	 * Create a ReportService from given parameters
	 * @param session http session.
	 * @return an instance of report service with configurations defined in parameters
	 * @throws FMSException throw exception if failed to authenticate the user
	 */
    	private ReportService getReportService(HttpSession session) throws FMSException {
		String realmId = (String)session.getAttribute("realmId");
		String accessToken = (String)session.getAttribute("access_token");
		ReportService service = helper.getReportService(realmId, accessToken);

		String startDate = (String) session.getAttribute("start_date");
		if (StringUtils.isNotEmpty(startDate)) {
			service.setStart_date(startDate);
		}

		String endDate = (String) session.getAttribute("end_date");
		if (StringUtils.isNotEmpty(endDate)) {
			service.setEnd_date(endDate);
		}

		String summarizeColumnBy = (String) session.getAttribute("summarize_column_by");
		if (StringUtils.isNotEmpty(summarizeColumnBy)) {
			service.setSummarize_column_by(summarizeColumnBy);
		}

		String accountingMethod = (String) session.getAttribute("accounting_method");
		if (StringUtils.isNotEmpty(accountingMethod)) {
			service.setAccounting_method(accountingMethod);
		}

		return service;
	}

	/**
	 * Get report by the given report name.
	 * @param service the report service
	 * @param reportName report name, e.g. "BalanceSheet", "ProfitAndLoss", etc.
	 * @return report in JSON format specified by the report name.
	 * @throws FMSException if failed to retrieve the report by report name.
	 */
	private String getReportString(ReportService service, String reportName) throws FMSException {
		Report report = service.executeReport(reportName);
		logger.info("ReportName -> name: " + report.getHeader().getReportName().toLowerCase());

		return processResponse(report);
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

package in.edu.ashoka.surf;

import java.io.IOException;


import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.annotation.*;

import in.edu.ashoka.surf.Config;


import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

@MultipartConfig
public class columnUpdateServlet extends HttpServlet {
    private static Log log = LogFactory.getLog(in.edu.ashoka.surf.columnUpdateServlet.class);

    public columnUpdateServlet() {
        super();
        // TODO Auto-generated constructor stub
    }

	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("text/html");
        request.getRequestDispatcher("index.jsp").include(request, response);
    }

	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        HttpSession session = request.getSession();
        String colToSortBy = "";
        for (String col: Config.actualColumns.get(session.getAttribute("datasetKey")))
        {
            if(request.getParameter(col+"Sort")==null)
            {
                colToSortBy = colToSortBy + col + ",";
            }
        }
        Config.sortColumns = new String[colToSortBy.split(",").length];
        Config.sortColumns = colToSortBy.split(",");
        String colsToRemove = "";
        for (String col: Config.actualColumns.get(session.getAttribute("datasetKey")))
        {
            if(request.getParameter(col)==null)
            {
                colsToRemove = colsToRemove + col + ",";
            }
        }
        String colsToRemoveArr[] = colsToRemove.split(",");
        for(int i=0; i<colsToRemoveArr.length; i++)
            Config.actualColumns.get(session.getAttribute("datasetKey")).remove(colsToRemoveArr[i]);
        request.getRequestDispatcher("select-op").forward(request, response);
	}

	public void destroy() {
	}
}
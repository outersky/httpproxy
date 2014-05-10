package cn.hillwind.app.proxy.ui

import javax.swing.JFrame
import javax.swing.JTextPane
import javax.swing.JTable
import javax.swing.JTabbedPane
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JSplitPane
import java.awt.Dimension
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableRowSorter
import javax.swing.ListSelectionModel
import javax.swing.JScrollPane
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.SwingUtilities
import javax.swing.JToolBar
import javax.swing.JButton
import javax.swing.Action
import javax.swing.AbstractAction
import java.awt.event.ActionEvent
import javax.swing.JFileChooser
import java.io.FileOutputStream
import javax.swing.UIManager
import javax.swing.JLabel
import java.awt.CardLayout
import javax.swing.ImageIcon
import java.util.zip.GZIPInputStream

import cn.hillwind.app.proxy.*

class HttpTableModel() : AbstractTableModel() {

    private val headers = array("#","ID","HOST","URL","METHOD","STATUS","LENGTH","START_TIME")

    override fun getColumnCount():Int { return 8 }
    override fun getRowCount():Int { return entities.size }

    private val entities = arrayListOf<HttpEntity>()

    fun get(index:Int):HttpEntity = entities[index]

    fun setHttpEntities(list : List<HttpEntity>){
        entities.clear()
        entities.addAll(list)
        fireTableDataChanged()
    }

    [suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")]
    override fun getValueAt(row:Int, col:Int):Any? {
        val entry = entities[row]

        // array("#","ID","HOST","URL","METHOD","STATUS","LENGTH","TIME")
        return when(col){
            0 -> Integer(row+1)
            1 -> entry.id
            2 -> entry.host
            3 -> entry.url
            4 -> entry.method
            5 -> entry.status
            6 -> entry.length
            7 -> entry.startTime.longFormat()
            else -> ""
        }
    }

    [suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")]
    override fun getColumnName(column:Int):String{
        return headers[column]
    }

    [suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")]
    override fun getColumnClass(columnIndex:Int) : Class<*> {
        return when(columnIndex){
            0,1,5,6 -> javaClass<Int>()
            else -> javaClass<String>()
        }
    }

    [suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")]
    override fun isCellEditable(rowIndex:Int,columnIndex:Int):Boolean = false
}

class PreviewPanel : JPanel(){
    val label = JLabel()
    val text = JTextPane()
    val layout = CardLayout();

    {
        setLayout(layout)
        add(JScrollPane(label))
        add(JScrollPane(text))
    }

    fun previewText(content:String){
        text.setText(content)
        layout.last(this)
    }

    fun previewImage(content:ByteArray){
        label.setIcon(ImageIcon(content))
        layout.first(this)
    }

}

class MainFrame(val title:String) : JFrame(title){

    val frame = this

    val dataModel = HttpTableModel()

    val sqlText = JTextPane() me {
        setText("where ID>0 order by START_TIME")

        addKeyListener(object : KeyAdapter(){
            override fun keyPressed(p0: KeyEvent) {
                if(p0.getKeyCode()==KeyEvent.VK_ENTER && p0.isControlDown()){
                    query()
                    return
                }
                super<KeyAdapter>.keyTyped(p0)
            }
        })
    }
    val rsTable = JTable(dataModel) me {
        setRowSorter(TableRowSorter(dataModel))
        setColumnSelectionAllowed(false)
        setCellSelectionEnabled(false)
        setRowSelectionAllowed(true)
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)

        val columnModel = getColumnModel()!!

        0..columnModel.getColumnCount()-1 forEach { i ->
            val col = columnModel.getColumn(i)!!
            // array("#","ID","HOST","URL","METHOD","STATUS","LENGTH","START_TIME")
            when(i){
                0 -> { col.setPreferredWidth(40);col.setMaxWidth(50) }
                1 -> { col.setPreferredWidth(40);col.setMaxWidth(50) }
                2 -> { col.setPreferredWidth(120);col.setMaxWidth(300) }
                3 -> { }
                4 -> { col.setPreferredWidth(30);col.setMaxWidth(50) }
                5 -> { col.setPreferredWidth(60);col.setMaxWidth(100) }
                6 -> { col.setPreferredWidth(70);col.setMaxWidth(70) }
                7 -> { col.setPreferredWidth(150);col.setMaxWidth(150)}
                else -> {}
            }
        }

        getSelectionModel()?.addListSelectionListener { e ->
            SwingUtilities.invokeLater {
                getSelectedRows().forEach {
                    fill(convertRowIndexToModel(it))
                }
            }
        }
    }

    val requestHeaderText = JTextPane()
    val ResponseHeaderText = JTextPane()
    val previewPanel = PreviewPanel()

    val detailPanel = JTabbedPane() me {
        addTab("RequestHeader",JScrollPane(requestHeaderText))
        addTab("ResponseHeader",JScrollPane(ResponseHeaderText))
        addTab("Response",previewPanel)
    }

    val toolbar = JToolBar() me {
        add(JButton(object : AbstractAction("Start"){
            var proxy:jProxy? = null

            override fun actionPerformed(p0: ActionEvent) {
                if(proxy!=null && proxy!!.isRunning()){
                    proxy!!.closeSocket()
                    putValue(Action.NAME,"Start")
                }else{
                    proxy = jProxy(18888, "", 0, 20000)
                    proxy!!.start()
                    putValue(Action.NAME,"Stop")
                }
            }
        }))
        add(JButton(object : AbstractAction("Start Monitor"){
            var monitoring = false
            override fun actionPerformed(p0: ActionEvent) {
                if(monitoring){
                    // stop
                    monitoring = false
                    putValue(Action.NAME,"Start Monitor")
                }else{
                    // start
                    monitoring = true
                    putValue(Action.NAME,"Stop Monitor")
                }
            }
        }))
        addSeparator()
        add(JButton(object : AbstractAction("Run SQL"){
            override fun actionPerformed(p0: ActionEvent) {
                query()
            }
        }))
        addSeparator()
        add(JButton(object : AbstractAction("Dump"){
            val fileChooser = JFileChooser()
            override fun actionPerformed(p0: ActionEvent) {
                if(rsTable.getSelectedRow()<0) return
                val index = rsTable.convertRowIndexToModel( rsTable.getSelectedRow() )
                val returnVal = fileChooser.showSaveDialog(frame)
                if(returnVal == JFileChooser.APPROVE_OPTION) {
                    dataModel[index].content?.inputStream?.copyTo(FileOutputStream(fileChooser.getSelectedFile()!!))
                }
            }
        }))
    }


    fun init(){
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())

        setSize(900,600)
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE)

        setLayout(BorderLayout())

        add(toolbar ,BorderLayout.NORTH)

        add(JSplitPane() me {
            setOrientation(JSplitPane.VERTICAL_SPLIT)
            setDividerLocation(100)
//            setDividerSize(8)
            setOneTouchExpandable(true)

            setTopComponent(JScrollPane(sqlText) me { setPreferredSize(Dimension(100,100)) } )

            setBottomComponent( JSplitPane() me {
                setOrientation(JSplitPane.VERTICAL_SPLIT)
                setDividerLocation(100)
//                setDividerSize(8)
                setOneTouchExpandable(true)

                setTopComponent(JScrollPane(rsTable) me { setPreferredSize(Dimension(100,100)) })
                setBottomComponent(detailPanel me { setPreferredSize(Dimension(100,100)) } )
            })

        },BorderLayout.CENTER)

    }

    fun query(){
        dataModel setHttpEntities Db.find(sqlText.getText()!!)
    }

    fun fill(index:Int){
        dataModel[index] self { he ->
            requestHeaderText.setText(he.requestHeader)
            ResponseHeaderText.setText(he.responseHeader)
            he.content ifNotNull { it ->
                var content = it
                if(he.contentEncoding.indexOf("gzip")>=0){
                    content = GZIPInputStream(it.inputStream).readBytes()
                }
                if (he.contentType.indexOf("image") >= 0) {
                    previewPanel.previewImage(content)
                }else{
                    previewPanel.previewText(String(content))
                }
            }
        }
    }
}

fun main(args:Array<String>){
    val frame = MainFrame("HttpProxy")
    Db.init()
    frame.init()
    frame.query()
    frame.setVisible(true)
}
